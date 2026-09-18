package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-upload progress and streams it to the browser via Server-Sent Events.
 *
 * MaterialController.uploadMaterial() is one synchronous request that runs several
 * sequential Claude calls (knowledge extraction, summarization, categorization,
 * question generation). Without this, the frontend has no way to know which step
 * is currently running and previously faked it with a timer.
 *
 * Flow: the browser generates a client-side uploadId, opens an SSE connection to
 * GET /api/materials/upload-stream/{uploadId} BEFORE posting the upload, then
 * includes the same uploadId as a form field on the POST (this uploadId is the
 * same one MaterialController already uses for /cancel-upload). As uploadMaterial()
 * reaches each stage, it calls publish(), which pushes an event down the
 * already-open SSE connection from the request-handling thread.
 */
@Component
public class UploadProgressService {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** Registers a new SSE connection for this uploadId. Call from the GET stream endpoint. */
    public SseEmitter subscribe(String uploadId) {
        SseEmitter emitter = new SseEmitter(2 * 60 * 1000L); // 2 min timeout — generous for slow Claude calls
        emitters.put(uploadId, emitter);

        emitter.onCompletion(() -> emitters.remove(uploadId));
        emitter.onTimeout(() -> { emitter.complete(); emitters.remove(uploadId); });
        emitter.onError(e -> emitters.remove(uploadId));

        publish(uploadId, "connected", "Connected — waiting for upload to start...");
        return emitter;
    }

    /** Pushes one progress update. No-ops silently if there's no live subscriber. */
    public void publish(String uploadId, String stage, String message) {
        if (uploadId == null || uploadId.isBlank()) return;
        SseEmitter emitter = emitters.get(uploadId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("progress").data(new ProgressEvent(stage, message, false, true)));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(uploadId);
        }
    }

    /** Sends the final event and closes the stream. Call exactly once per upload, success or failure. */
    public void complete(String uploadId, boolean success, String message) {
        if (uploadId == null || uploadId.isBlank()) return;
        SseEmitter emitter = emitters.remove(uploadId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(new ProgressEvent(success ? "done" : "error", message, true, success)));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            // Client already gone.
        }
    }

    public static class ProgressEvent {
        public final String stage;
        public final String message;
        public final boolean finished;
        public final boolean success;

        public ProgressEvent(String stage, String message, boolean finished, boolean success) {
            this.stage = stage;
            this.message = message;
            this.finished = finished;
            this.success = success;
        }
    }
}