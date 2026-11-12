package com.dws.trade.query;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Pushes outcomes to connected dashboards over Server-Sent Events. */
@Component
public class LiveFeed {

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        subscribers.add(emitter);
        send(emitter, "connected", "{}");
        return emitter;
    }

    public void publish(String name, String json) {
        for (SseEmitter emitter : subscribers) {
            send(emitter, name, json);
        }
    }

    private void send(SseEmitter emitter, String name, String json) {
        try {
            emitter.send(SseEmitter.event().name(name).data(json));
        } catch (IOException | IllegalStateException e) {
            // The browser went away; drop the subscriber.
            subscribers.remove(emitter);
        }
    }
}
