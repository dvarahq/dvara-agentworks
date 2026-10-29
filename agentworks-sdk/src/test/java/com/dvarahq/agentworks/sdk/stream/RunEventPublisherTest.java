package com.dvarahq.agentworks.sdk.stream;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RunEventPublisherTest {
    @Test void reconnectResumesExactSequenceAndHonorsOneItemDemand() throws Exception {
        List<Long> resumePositions = new ArrayList<>();
        AtomicInteger connection = new AtomicInteger();
        RunStreamTransport transport = (run, options, after) -> {
            resumePositions.add(after);
            String body = connection.getAndIncrement() == 0
                    ? event(run, 1) + event(run, 2)
                    : event(run, 2) + event(run, 3) + "event: stream-end\ndata: {}\n\n";
            return opened(body);
        };
        RecordingSubscriber subscriber = new RecordingSubscriber(true);
        new RunEventPublisher("run-1", new RunStreamOptions("client-1", 0, Duration.ZERO, 2), transport)
                .subscribe(subscriber);

        assertThat(subscriber.done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(subscriber.error).isNull();
        assertThat(subscriber.events.stream().filter(e -> e.kind() == TypedRunEvent.Kind.RUN_EVENT)
                .map(TypedRunEvent::sequence)).containsExactly(1L, 2L, 3L);
        assertThat(resumePositions).containsExactly(0L, 2L);
    }

    @Test void aSequenceGapFailsClosed() throws Exception {
        RunStreamTransport transport = (run, options, after) -> opened(event(run, 2));
        RecordingSubscriber subscriber = new RecordingSubscriber(false);
        new RunEventPublisher("run-1", RunStreamOptions.defaults(), transport).subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);

        assertThat(subscriber.done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(subscriber.error).isInstanceOf(RunStreamException.class);
        assertThat(((RunStreamException) subscriber.error).code()).isEqualTo("STREAM_SEQUENCE_GAP");
    }

    private static String event(String run, long sequence) {
        return "id: " + run + ":" + sequence + "\nevent: RUN_ACTIVE\ndata: {\"sequence\":" + sequence + "}\n\n";
    }

    private static RunStreamTransport.Connection opened(String value) {
        return new RunStreamTransport.Connection() {
            private final BufferedReader reader = new BufferedReader(new StringReader(value));
            @Override public int status() { return 200; }
            @Override public BufferedReader reader() { return reader; }
            @Override public void close() { }
        };
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<TypedRunEvent> {
        private final boolean oneAtATime;
        private final List<TypedRunEvent> events = new ArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Flow.Subscription subscription;
        private volatile Throwable error;
        private RecordingSubscriber(boolean oneAtATime) { this.oneAtATime = oneAtATime; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (oneAtATime) subscription.request(1);
        }
        @Override public void onNext(TypedRunEvent item) {
            events.add(item);
            if (oneAtATime) subscription.request(1);
        }
        @Override public void onError(Throwable throwable) { error = throwable; done.countDown(); }
        @Override public void onComplete() { done.countDown(); }
    }
}
