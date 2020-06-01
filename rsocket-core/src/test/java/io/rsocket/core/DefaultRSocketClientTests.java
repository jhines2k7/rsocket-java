package io.rsocket.core;
/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static io.rsocket.frame.FrameHeaderCodec.frameType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.RSocketClient;
import io.rsocket.TestScheduler;
import io.rsocket.frame.ErrorFrameCodec;
import io.rsocket.frame.FrameHeaderCodec;
import io.rsocket.frame.FrameType;
import io.rsocket.frame.PayloadFrameCodec;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.internal.subscriber.AssertSubscriber;
import io.rsocket.lease.RequesterLeaseHandler;
import io.rsocket.util.ByteBufPayload;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.Assumptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.runners.model.Statement;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.SignalType;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import reactor.test.util.RaceTestUtils;
import reactor.util.context.Context;
import reactor.util.retry.Retry;

public class DefaultRSocketClientTests {

  ClientSocketRule rule;

  @BeforeEach
  public void setUp() throws Throwable {
    Hooks.onNextDropped(ReferenceCountUtil::safeRelease);
    Hooks.onErrorDropped((t) -> {});
    rule = new ClientSocketRule();
    rule.apply(
            new Statement() {
              @Override
              public void evaluate() {}
            },
            null)
        .evaluate();
  }

  @AfterEach
  public void tearDown() {
    Hooks.resetOnErrorDropped();
    Hooks.resetOnNextDropped();
  }

  static Stream<Arguments> interactions() {
    return Stream.of(
        Arguments.of(
            (BiFunction<RSocketClient, Publisher<Payload>, Publisher<?>>)
                (client, payload) -> client.fireAndForget(Mono.fromDirect(payload)),
            FrameType.REQUEST_FNF),
        Arguments.of(
            (BiFunction<RSocketClient, Publisher<Payload>, Publisher<?>>)
                (client, payload) -> client.requestResponse(Mono.fromDirect(payload)),
            FrameType.REQUEST_RESPONSE),
        Arguments.of(
            (BiFunction<RSocketClient, Publisher<Payload>, Publisher<?>>)
                (client, payload) -> client.requestStream(Mono.fromDirect(payload)),
            FrameType.REQUEST_STREAM),
        Arguments.of(
            (BiFunction<RSocketClient, Publisher<Payload>, Publisher<?>>)
                RSocketClient::requestChannel,
            FrameType.REQUEST_CHANNEL),
        Arguments.of(
            (BiFunction<RSocketClient, Publisher<Payload>, Publisher<?>>)
                (client, payload) -> client.metadataPush(Mono.fromDirect(payload)),
            FrameType.METADATA_PUSH));
  }

  @ParameterizedTest
  @MethodSource("interactions")
  public void shouldSentFrameOnResolution(
      BiFunction<RSocketClient, Publisher<Payload>, Publisher<?>> request, FrameType requestType) {
    Payload payload = ByteBufPayload.create("test", "testMetadata");
    TestPublisher<Payload> testPublisher =
        TestPublisher.createNoncompliant(TestPublisher.Violation.DEFER_CANCELLATION);

    Publisher<?> publisher = request.apply(rule.client, testPublisher);

    StepVerifier.create(publisher)
        .expectSubscription()
        .then(() -> Assertions.assertThat(rule.connection.getSent()).isEmpty())
        .then(
            () -> {
              if (requestType != FrameType.REQUEST_CHANNEL) {
                testPublisher.next(payload);
              }
            })
        .then(() -> rule.delayer.run())
        .then(
            () -> {
              if (requestType == FrameType.REQUEST_CHANNEL) {
                testPublisher.next(payload);
              }
            })
        .then(testPublisher::complete)
        .then(
            () ->
                Assertions.assertThat(rule.connection.getSent())
                    .hasSize(1)
                    .first()
                    .matches(bb -> FrameHeaderCodec.frameType(bb).equals(requestType))
                    .matches(ReferenceCounted::release))
        .then(
            () -> {
              if (requestType != FrameType.REQUEST_FNF && requestType != FrameType.METADATA_PUSH) {
                rule.connection.addToReceivedBuffer(
                    PayloadFrameCodec.encodeComplete(rule.allocator, 1));
              }
            })
        .expectComplete()
        .verify(Duration.ofMillis(1000));

    rule.allocator.assertHasNoLeaks();
  }

  @ParameterizedTest
  @MethodSource("interactions")
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void shouldHaveNoLeaksOnPayloadInCaseOfRacingOfOnNextAndCancel(
      BiFunction<RSocketClient, Publisher<Payload>, Publisher<?>> request, FrameType requestType)
      throws Throwable {
    Assumptions.assumeThat(requestType).isNotEqualTo(FrameType.REQUEST_CHANNEL);

    for (int i = 0; i < 10000; i++) {
      ClientSocketRule rule = new ClientSocketRule();
      rule.apply(
              new Statement() {
                @Override
                public void evaluate() {}
              },
              null)
          .evaluate();
      Payload payload = ByteBufPayload.create("test", "testMetadata");
      TestPublisher<Payload> testPublisher =
          TestPublisher.createNoncompliant(TestPublisher.Violation.DEFER_CANCELLATION);
      AssertSubscriber assertSubscriber = AssertSubscriber.create(0);

      Publisher<?> publisher = request.apply(rule.client, testPublisher);
      publisher.subscribe(assertSubscriber);

      testPublisher.assertWasNotRequested();

      assertSubscriber.request(1);

      testPublisher.assertWasRequested();
      testPublisher.assertMaxRequested(1);
      testPublisher.assertMinRequested(1);

      RaceTestUtils.race(
          () -> {
            testPublisher.next(payload);
            rule.delayer.run();
          },
          assertSubscriber::cancel);

      Collection<ByteBuf> sent = rule.connection.getSent();
      if (sent.size() == 1) {
        Assertions.assertThat(sent)
            .allMatch(bb -> FrameHeaderCodec.frameType(bb).equals(requestType))
            .allMatch(ReferenceCounted::release);
      } else if (sent.size() == 2) {
        Assertions.assertThat(sent)
            .first()
            .matches(bb -> FrameHeaderCodec.frameType(bb).equals(requestType))
            .matches(ReferenceCounted::release);
        Assertions.assertThat(sent)
            .element(1)
            .matches(bb -> FrameHeaderCodec.frameType(bb).equals(FrameType.CANCEL))
            .matches(ReferenceCounted::release);
      } else {
        Assertions.assertThat(sent).isEmpty();
      }

      rule.allocator.assertHasNoLeaks();
    }
  }

  @ParameterizedTest
  @MethodSource("interactions")
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void shouldHaveNoLeaksOnPayloadInCaseOfRacingOfRequestAndCancel(
      BiFunction<RSocketClient, Publisher<Payload>, Publisher<?>> request, FrameType requestType)
      throws Throwable {
    Assumptions.assumeThat(requestType).isNotEqualTo(FrameType.REQUEST_CHANNEL);

    for (int i = 0; i < 10000; i++) {
      ClientSocketRule rule = new ClientSocketRule();
      rule.apply(
              new Statement() {
                @Override
                public void evaluate() {}
              },
              null)
          .evaluate();
      ByteBuf dataBuffer = rule.allocator.buffer();
      dataBuffer.writeCharSequence("test", CharsetUtil.UTF_8);

      ByteBuf metadataBuffer = rule.allocator.buffer();
      metadataBuffer.writeCharSequence("testMetadata", CharsetUtil.UTF_8);

      Payload payload = ByteBufPayload.create(dataBuffer, metadataBuffer);
      AssertSubscriber assertSubscriber = AssertSubscriber.create(0);

      Publisher<?> publisher = request.apply(rule.client, Mono.just(payload));
      publisher.subscribe(assertSubscriber);

      RaceTestUtils.race(
          () -> {
            assertSubscriber.request(1);
            rule.delayer.run();
          },
          assertSubscriber::cancel);

      Collection<ByteBuf> sent = rule.connection.getSent();
      if (sent.size() == 1) {
        Assertions.assertThat(sent)
            .allMatch(bb -> FrameHeaderCodec.frameType(bb).equals(requestType))
            .allMatch(ReferenceCounted::release);
      } else if (sent.size() == 2) {
        Assertions.assertThat(sent)
            .first()
            .matches(bb -> FrameHeaderCodec.frameType(bb).equals(requestType))
            .matches(ReferenceCounted::release);
        Assertions.assertThat(sent)
            .element(1)
            .matches(bb -> FrameHeaderCodec.frameType(bb).equals(FrameType.CANCEL))
            .matches(ReferenceCounted::release);
      } else {
        Assertions.assertThat(sent).isEmpty();
      }

      rule.allocator.assertHasNoLeaks();
    }
  }

  @ParameterizedTest
  @MethodSource("interactions")
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void shouldPropagateDownstreamContext(
      BiFunction<RSocketClient, Publisher<Payload>, Publisher<?>> request, FrameType requestType) {
    Assumptions.assumeThat(requestType).isNotEqualTo(FrameType.REQUEST_CHANNEL);

    ByteBuf dataBuffer = rule.allocator.buffer();
    dataBuffer.writeCharSequence("test", CharsetUtil.UTF_8);

    ByteBuf metadataBuffer = rule.allocator.buffer();
    metadataBuffer.writeCharSequence("testMetadata", CharsetUtil.UTF_8);

    Payload payload = ByteBufPayload.create(dataBuffer, metadataBuffer);
    AssertSubscriber assertSubscriber = new AssertSubscriber(Context.of("test", "test"));

    Context[] receivedContext = new Context[1];
    Publisher<?> publisher =
        request.apply(
            rule.client,
            Mono.just(payload)
                .mergeWith(
                    Mono.subscriberContext()
                        .doOnNext(c -> receivedContext[0] = c)
                        .then(Mono.empty())));
    publisher.subscribe(assertSubscriber);

    rule.delayer.run();

    Collection<ByteBuf> sent = rule.connection.getSent();
    if (sent.size() == 1) {
      Assertions.assertThat(sent)
          .allMatch(bb -> FrameHeaderCodec.frameType(bb).equals(requestType))
          .allMatch(ReferenceCounted::release);
    } else if (sent.size() == 2) {
      Assertions.assertThat(sent)
          .first()
          .matches(bb -> FrameHeaderCodec.frameType(bb).equals(requestType))
          .matches(ReferenceCounted::release);
      Assertions.assertThat(sent)
          .element(1)
          .matches(bb -> FrameHeaderCodec.frameType(bb).equals(FrameType.CANCEL))
          .matches(ReferenceCounted::release);
    } else {
      Assertions.assertThat(sent).isEmpty();
    }

    Assertions.assertThat(receivedContext)
        .hasSize(1)
        .allSatisfy(
            c ->
                Assertions.assertThat(
                        c.stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                    .containsKeys("test", DefaultRSocketClient.ON_DISCARD_KEY));

    rule.allocator.assertHasNoLeaks();
  }

  @ParameterizedTest
  @MethodSource("interactions")
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void shouldSupportMultiSubscriptionOnTheSameInteractionPublisher(
      BiFunction<RSocketClient, Publisher<Payload>, Publisher<?>> request, FrameType requestType) {
    AtomicBoolean once1 = new AtomicBoolean();
    AtomicBoolean once2 = new AtomicBoolean();
    Mono<Payload> source =
        Mono.fromCallable(
                () -> {
                  if (!once1.getAndSet(true)) {
                    throw new IllegalStateException("test");
                  }
                  return ByteBufPayload.create("test", "testMetadata");
                })
            .doFinally(
                st -> {
                  rule.delayer.run();
                  if (requestType != FrameType.METADATA_PUSH
                      && requestType != FrameType.REQUEST_FNF) {
                    if (st != SignalType.ON_ERROR) {
                      if (!once2.getAndSet(true)) {
                        rule.connection.addToReceivedBuffer(
                            ErrorFrameCodec.encode(
                                rule.allocator, 1, new IllegalStateException("test")));
                      } else {
                        rule.connection.addToReceivedBuffer(
                            PayloadFrameCodec.encodeComplete(rule.allocator, 3));
                      }
                    }
                  }
                });
    AssertSubscriber assertSubscriber = AssertSubscriber.create(0);

    Publisher<?> publisher = request.apply(rule.client, source);
    if (publisher instanceof Mono) {
      ((Mono) publisher)
          .retryWhen(Retry.backoff(3, Duration.ofMillis(100)))
          .subscribe(assertSubscriber);
    } else {
      ((Flux) publisher)
          .retryWhen(Retry.backoff(3, Duration.ofMillis(100)))
          .subscribe(assertSubscriber);
    }

    assertSubscriber.request(1);

    if (requestType == FrameType.REQUEST_CHANNEL) {
      rule.delayer.run();
    }

    assertSubscriber.await(Duration.ofSeconds(10)).assertComplete();

    Collection<ByteBuf> sent = rule.connection.getSent();
    Assertions.assertThat(sent)
        .allMatch(bb -> FrameHeaderCodec.frameType(bb).equals(requestType))
        .allMatch(ReferenceCounted::release);

    rule.allocator.assertHasNoLeaks();
  }

  @Test
  public void shouldBeAbleToResolveOriginalSource() {
    AssertSubscriber<RSocket> assertSubscriber = AssertSubscriber.create(0);
    rule.client.source().subscribe(assertSubscriber);

    assertSubscriber.assertNotTerminated();

    rule.delayer.run();

    assertSubscriber.request(1);

    assertSubscriber.assertTerminated().assertValueCount(1);

    AssertSubscriber<RSocket> assertSubscriber1 = AssertSubscriber.create();

    rule.client.source().subscribe(assertSubscriber1);

    assertSubscriber1.assertTerminated().assertValueCount(1);

    Assertions.assertThat(assertSubscriber1.values()).isEqualTo(assertSubscriber.values());
  }

  @Test
  public void shouldDisposeOriginalSource() {
    AssertSubscriber<RSocket> assertSubscriber = AssertSubscriber.create();
    rule.client.source().subscribe(assertSubscriber);
    rule.delayer.run();
    assertSubscriber.assertTerminated().assertValueCount(1);

    rule.client.dispose();

    Assertions.assertThat(rule.client.isDisposed()).isTrue();

    AssertSubscriber<RSocket> assertSubscriber1 = AssertSubscriber.create();

    rule.client.source().subscribe(assertSubscriber1);

    assertSubscriber1
        .assertTerminated()
        .assertError(CancellationException.class)
        .assertErrorMessage("Disposed");

    Assertions.assertThat(rule.socket.isDisposed()).isTrue();
  }

  @Test
  public void shouldDisposeOriginalSourceIfRacing() throws Throwable {
    for (int i = 0; i < 10000; i++) {
      ClientSocketRule rule = new ClientSocketRule();
      rule.apply(
              new Statement() {
                @Override
                public void evaluate() {}
              },
              null)
          .evaluate();

      AssertSubscriber<RSocket> assertSubscriber = AssertSubscriber.create();
      rule.client.source().subscribe(assertSubscriber);

      RaceTestUtils.race(rule.delayer, () -> rule.client.dispose());

      assertSubscriber.assertTerminated();

      Assertions.assertThat(rule.client.isDisposed()).isTrue();
      Assertions.assertThat(rule.socket.isDisposed()).isTrue();

      AssertSubscriber<RSocket> assertSubscriber1 = AssertSubscriber.create();

      rule.client.source().subscribe(assertSubscriber1);

      assertSubscriber1
          .assertTerminated()
          .assertError(CancellationException.class)
          .assertErrorMessage("Disposed");
    }
  }

  public static class ClientSocketRule extends AbstractSocketRule<RSocketRequester> {

    protected RSocketClient client;
    protected Runnable delayer;
    protected MonoProcessor<RSocket> producer;

    @Override
    protected void init() {
      super.init();
      delayer = () -> producer.onNext(socket);
      producer = MonoProcessor.create();
      client =
          new DefaultRSocketClient(
              producer
                  .doOnCancel(() -> socket.dispose())
                  .doOnDiscard(Disposable.class, Disposable::dispose));
    }

    @Override
    protected RSocketRequester newRSocket() {
      return new RSocketRequester(
          connection,
          PayloadDecoder.ZERO_COPY,
          StreamIdSupplier.clientSupplier(),
          0,
          0,
          0,
          null,
          RequesterLeaseHandler.None,
          TestScheduler.INSTANCE);
    }

    public int getStreamIdForRequestType(FrameType expectedFrameType) {
      assertThat("Unexpected frames sent.", connection.getSent(), hasSize(greaterThanOrEqualTo(1)));
      List<FrameType> framesFound = new ArrayList<>();
      for (ByteBuf frame : connection.getSent()) {
        FrameType frameType = frameType(frame);
        if (frameType == expectedFrameType) {
          return FrameHeaderCodec.streamId(frame);
        }
        framesFound.add(frameType);
      }
      throw new AssertionError(
          "No frames sent with frame type: "
              + expectedFrameType
              + ", frames found: "
              + framesFound);
    }
  }
}