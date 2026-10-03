package com.orderflow.service;

import com.orderflow.dto.NotificationResponse;
import com.orderflow.model.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class SseNotificationService {

    // 30 minute timeout per SSE connection
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String userEmail) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitters.computeIfAbsent(userEmail, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(userEmail, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            removeEmitter(userEmail, emitter);
        });
        emitter.onError(e -> removeEmitter(userEmail, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data("Connected to OrderFlow live notifications stream"));
        } catch (IOException e) {
            removeEmitter(userEmail, emitter);
        }

        log.info("SSE subscription opened for user [{}]. Active connections for user: {}",
                userEmail, emitters.getOrDefault(userEmail, List.of()).size());

        return emitter;
    }

    public void sendNotification(String userEmail, Notification notification) {
        if (userEmail == null) return;

        List<SseEmitter> userEmitters = emitters.get(userEmail);
        if (userEmitters == null || userEmitters.isEmpty()) {
            return;
        }

        NotificationResponse payload = NotificationResponse.builder()
                .id(notification.getId())
                .userId(notification.getUserId())
                .userEmail(notification.getUserEmail())
                .orderId(notification.getOrderId())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .type(notification.getType())
                .isRead(notification.getIsRead())
                .createdAt(notification.getCreatedAt())
                .build();

        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("NOTIFICATION")
                        .data(payload));
            } catch (Exception e) {
                log.warn("Failed to dispatch SSE event to [{}], removing stale connection: {}", userEmail, e.getMessage());
                removeEmitter(userEmail, emitter);
            }
        }
    }

    @Scheduled(fixedRate = 25000)
    public void sendHeartbeat() {
        emitters.forEach((email, emitterList) -> {
            for (SseEmitter emitter : emitterList) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("PING")
                            .data("heartbeat"));
                } catch (Exception e) {
                    removeEmitter(email, emitter);
                }
            }
        });
    }

    private void removeEmitter(String userEmail, SseEmitter emitter) {
        List<SseEmitter> userEmitters = emitters.get(userEmail);
        if (userEmitters != null) {
            userEmitters.remove(emitter);
            if (userEmitters.isEmpty()) {
                emitters.remove(userEmail);
            }
        }
    }

    public int getActiveSubscriberCount() {
        return emitters.values().stream().mapToInt(List::size).sum();
    }
}
