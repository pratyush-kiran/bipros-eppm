package com.bipros.hds.application.ingestion;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ProgressStreamRegistry {

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> byVersion = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID versionId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — caller closes
        byVersion.computeIfAbsent(versionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(versionId, emitter));
        emitter.onTimeout(() -> remove(versionId, emitter));
        emitter.onError(t -> remove(versionId, emitter));
        return emitter;
    }

    public void publish(IngestionProgressEvent ev) {
        List<SseEmitter> subs = byVersion.get(ev.versionId());
        if (subs == null) return;
        for (SseEmitter e : subs) {
            try { e.send(SseEmitter.event().name("progress").data(ev)); }
            catch (IOException ex) { remove(ev.versionId(), e); }
        }
    }

    private void remove(UUID id, SseEmitter e) {
        var subs = byVersion.get(id);
        if (subs != null) subs.remove(e);
    }
}
