package com.dws.trade.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** One slow or broken browser must not hold up the projection or the other browsers. */
class LiveFeedTest {

    private static final class Recording implements LiveFeed.Sender {
        final List<String> received = new CopyOnWriteArrayList<>();

        @Override
        public void send(String name, String data) {
            received.add(name + ":" + data);
        }

        @Override
        public void close() {
        }
    }

    @Test
    void deliversEveryMessageToEverySubscriberInOrder() {
        LiveFeed feed = new LiveFeed(10, 100, 5_000);
        Recording first = new Recording();
        Recording second = new Recording();
        feed.subscribe(first);
        feed.subscribe(second);

        for (int i = 0; i < 20; i++) {
            feed.publish("trade-event", "{\"n\":" + i + "}");
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(first.received).hasSize(21).first().isEqualTo("connected:{}");
            assertThat(second.received).hasSize(21);
        });
        assertThat(first.received.get(1)).isEqualTo("trade-event:{\"n\":0}");
        assertThat(first.received.get(20)).isEqualTo("trade-event:{\"n\":19}");
    }

    @Test
    void aStuckSubscriberNeverBlocksPublishingAndIsDroppedWhileABusyOneIsKept() throws Exception {
        LiveFeed feed = new LiveFeed(10, 8, 300);
        CountDownLatch stuck = new CountDownLatch(1);
        LiveFeed.Sender blocked = new LiveFeed.Sender() {
            @Override
            public void send(String name, String data) throws IOException {
                try {
                    stuck.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }

            @Override
            public void close() {
                stuck.countDown();
            }
        };
        Recording healthy = new Recording();
        feed.subscribe(blocked);
        feed.subscribe(healthy);

        long started = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            feed.publish("trade-event", "{\"n\":" + i + "}");
        }
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).isLessThan(1_000);
        // The burst overflowed both small queues, but only the stuck subscriber has stopped making progress.
        assertThat(feed.subscriberCount()).isEqualTo(2);
        Thread.sleep(400);
        feed.heartbeat();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(feed.subscriberCount()).isEqualTo(1));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(healthy.received).contains("trade-event:{\"n\":999}", "heartbeat:{}"));
    }

    @Test
    void dropsASubscriberStuckInASendEvenWhenItsQueueHasRoom() throws Exception {
        // A queue far larger than the traffic: "queue full" never happens, yet the subscriber is stuck.
        LiveFeed feed = new LiveFeed(10, 100, 300);
        CountDownLatch stuck = new CountDownLatch(1);
        feed.subscribe(new LiveFeed.Sender() {
            @Override
            public void send(String name, String data) throws IOException {
                try {
                    stuck.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }

            @Override
            public void close() {
                stuck.countDown();
            }
        });
        Recording healthy = new Recording();
        feed.subscribe(healthy);
        feed.publish("trade-event", "{}");

        Thread.sleep(500);
        feed.heartbeat();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(feed.subscriberCount()).isEqualTo(1));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(healthy.received).contains("trade-event:{}", "heartbeat:{}"));
    }

    @Test
    void dropsASubscriberWhoseConnectionFails() {
        LiveFeed feed = new LiveFeed(10, 10, 5_000);
        feed.subscribe(new LiveFeed.Sender() {
            @Override
            public void send(String name, String data) throws IOException {
                throw new IOException("browser went away");
            }

            @Override
            public void close() {
            }
        });

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(feed.subscriberCount()).isZero());
    }

    @Test
    void sendsAHeartbeatSoIdleConnectionsStayOpen() {
        LiveFeed feed = new LiveFeed(10, 10, 5_000);
        Recording recording = new Recording();
        feed.subscribe(recording);

        feed.heartbeat();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(recording.received).contains("heartbeat:{}"));
    }

    @Test
    void refusesSubscribersBeyondTheLimit() {
        LiveFeed feed = new LiveFeed(2, 10, 5_000);
        feed.subscribe(new Recording());
        feed.subscribe(new Recording());

        assertThatThrownBy(() -> feed.subscribe(new Recording())).isInstanceOf(LiveFeed.TooManySubscribersException.class);
        assertThat(feed.subscriberCount()).isEqualTo(2);
    }
}
