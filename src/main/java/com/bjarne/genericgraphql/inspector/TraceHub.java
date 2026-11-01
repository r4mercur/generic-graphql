package com.bjarne.genericgraphql.inspector;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Haelt die letzten Mitschnitte und verteilt neue per Server-Sent Events an alle offenen
 * Inspector-Seiten. Gesendet wird aus einem eigenen Thread - ein langsamer Browser bremst
 * keinen GraphQL-Request.
 */
@Component
@ConditionalOnProperty(name = "inspector.enabled", havingValue = "true", matchIfMissing = true)
public class TraceHub {

    private static final Logger log = LoggerFactory.getLogger(TraceHub.class);
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final int capacity;
    private final AtomicLong ids = new AtomicLong();
    private final Deque<TraceView> recent = new ArrayDeque<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "inspector-sse");
        thread.setDaemon(true);
        return thread;
    });

    public TraceHub(@Value("${inspector.max-traces:50}") int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Ein Request hat mit der Ausfuehrung begonnen. */
    void started(TraceCollector trace) {
        trace.id(ids.incrementAndGet());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", trace.id());
        event.put("operation", trace.operationName());
        event.put("kind", trace.kind());
        event.put("startedAt", trace.startedAt());
        broadcast("start", event);
    }

    /** Ein Request ist fertig: aufheben und verteilen. */
    void finished(TraceCollector trace, TraceView view) {
        TraceView published = view;
        if (view.id() == 0) {
            trace.id(ids.incrementAndGet());
            published = withId(view, trace.id());
        }
        synchronized (recent) {
            recent.addFirst(published);
            while (recent.size() > capacity) {
                recent.removeLast();
            }
        }
        broadcast("trace", published);
    }

    /** Neueste zuerst. */
    public List<TraceView> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    public Optional<TraceView> find(long id) {
        synchronized (recent) {
            return recent.stream().filter(trace -> trace.id() == id).findFirst();
        }
    }

    public void clear() {
        synchronized (recent) {
            recent.clear();
        }
        broadcast("cleared", Map.of());
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(error -> emitters.remove(emitter));
        sender.execute(() -> send(emitter, "hello", Map.of("traces", recent().size())));
        return emitter;
    }

    int subscribers() {
        return emitters.size();
    }

    private void broadcast(String name, Object data) {
        sender.execute(() -> {
            for (SseEmitter emitter : emitters) {
                send(emitter, name, data);
            }
        });
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException ex) {
            log.debug("Inspector: SSE-Empfaenger getrennt ({})", ex.toString());
            emitters.remove(emitter);
        }
    }

    private static TraceView withId(TraceView view, long id) {
        return new TraceView(id, view.operation(), view.kind(), view.query(), view.startedAt(), view.totalMs(),
                view.status(), view.errorCount(), view.sqlCount(), view.remoteCount(), view.thread(), view.loaders(),
                view.nodes(), view.spans(), view.marks(), view.response());
    }

    @PreDestroy
    void shutdown() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // Verbindung ist schon zu
            }
        }
        sender.shutdownNow();
    }
}
