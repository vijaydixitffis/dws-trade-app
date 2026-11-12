package com.dws.trade.query;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes outcomes to connected dashboards over Server-Sent Events. Each browser has its own
 * bounded queue and writer thread, so a slow or stuck browser cannot hold up the projection
 * (which publishes from the Kafka listener thread) or any other browser.
 * <p>
 * The messages are "something changed" signals and the dashboard refetches the state, so when a
 * queue is full the oldest signal is discarded. A subscriber that has made no progress for
 * {@code stall-ms} while its queue is full is stuck and is dropped; its browser reconnects.
 */
@Component
public class LiveFeed {

    /** Where a subscriber's messages go. */
    interface Sender {
        void send(String name, String data) throws IOException;

        void close();
    }

    public static class TooManySubscribersException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TooManySubscribersException() {
            super("The live feed is at its limit of connected dashboards");
        }
    }

    private record Message(String name, String data) {
    }

    private final class Subscriber {
        final Sender sender;
        final BlockingQueue<Message> queue;
        final Thread writer;
        volatile boolean closed;
        /** When the writer last finished a send (or when the subscriber joined). */
        volatile long lastProgressNanos = System.nanoTime();

        Subscriber(Sender sender, int capacity) {
            this.sender = sender;
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.writer = Thread.ofVirtual().unstarted(this::write);
        }

        private void write() {
            try {
                while (!closed) {
                    Message message = queue.take();
                    sender.send(message.name(), message.data());
                    lastProgressNanos = System.nanoTime();
                }
            } catch (IOException | RuntimeException e) {
                drop(this);
            } catch (InterruptedException e) {
                // dropped while waiting
            }
        }
    }

    private final int maxSubscribers;
    private final int queueCapacity;
    private final long stallNanos;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public LiveFeed(@Value("${trade.live-feed.max-subscribers:200}") int maxSubscribers,
            @Value("${trade.live-feed.queue-capacity:256}") int queueCapacity,
            @Value("${trade.live-feed.stall-ms:5000}") long stallMillis) {
        this.maxSubscribers = maxSubscribers;
        this.queueCapacity = queueCapacity;
        this.stallNanos = stallMillis * 1_000_000;
    }

    /** A new browser connection. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        Runnable unsubscribe = subscribe(new Sender() {
            @Override
            public void send(String name, String data) throws IOException {
                try {
                    emitter.send(SseEmitter.event().name(name).data(data));
                } catch (IllegalStateException e) {
                    throw new IOException(e);
                }
            }

            @Override
            public void close() {
                emitter.complete();
            }
        });
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(e -> unsubscribe.run());
        return emitter;
    }

    /** @return a handle that removes the subscriber */
    synchronized Runnable subscribe(Sender sender) {
        if (subscribers.size() >= maxSubscribers) {
            throw new TooManySubscribersException();
        }
        Subscriber subscriber = new Subscriber(sender, queueCapacity);
        subscriber.queue.offer(new Message("connected", "{}"));
        subscribers.add(subscriber);
        subscriber.writer.start();
        return () -> drop(subscriber);
    }

    /** Hands the message to every subscriber's queue and returns at once. */
    public void publish(String name, String json) {
        Message message = new Message(name, json);
        for (Subscriber subscriber : subscribers) {
            if (subscriber.queue.offer(message)) {
                continue;
            }
            if (System.nanoTime() - subscriber.lastProgressNanos > stallNanos) {
                drop(subscriber);
            } else {
                // Busy, not stuck: keep the newest signal and discard the oldest.
                subscriber.queue.poll();
                subscriber.queue.offer(message);
            }
        }
    }

    /** Keeps idle connections open through proxies that close silent ones. */
    @Scheduled(fixedDelayString = "${trade.live-feed.heartbeat-ms:15000}")
    void heartbeat() {
        publish("heartbeat", "{}");
    }

    int subscriberCount() {
        return subscribers.size();
    }

    private void drop(Subscriber subscriber) {
        if (subscribers.remove(subscriber)) {
            subscriber.closed = true;
            subscriber.writer.interrupt();
            subscriber.sender.close();
        }
    }
}
