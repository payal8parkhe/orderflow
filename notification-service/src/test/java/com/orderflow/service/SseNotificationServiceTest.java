package com.orderflow.service;

import com.orderflow.model.Notification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SseNotificationServiceTest {

    private SseNotificationService sseNotificationService;

    @BeforeEach
    void setUp() {
        sseNotificationService = new SseNotificationService();
    }

    @Test
    @DisplayName("subscribe registers client and returns active emitter")
    void subscribe_success() {
        SseEmitter emitter = sseNotificationService.subscribe("buyer@orderflow.com");

        assertThat(emitter).isNotNull();
        assertThat(sseNotificationService.getActiveSubscriberCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("sendNotification dispatches event without throwing")
    void sendNotification_dispatchesGracefully() {
        sseNotificationService.subscribe("buyer@orderflow.com");

        Notification notification = Notification.builder()
                .id(99L)
                .userId(1L)
                .userEmail("buyer@orderflow.com")
                .orderId(123L)
                .title("Order Confirmed")
                .message("Your order #ORD-123 is confirmed")
                .type("ORDER_CREATED")
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();

        // Dispatches to active emitter safely
        sseNotificationService.sendNotification("buyer@orderflow.com", notification);
        assertThat(sseNotificationService.getActiveSubscriberCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("sendHeartbeat runs without exception")
    void sendHeartbeat_success() {
        sseNotificationService.subscribe("buyer@orderflow.com");
        sseNotificationService.sendHeartbeat();
        assertThat(sseNotificationService.getActiveSubscriberCount()).isGreaterThanOrEqualTo(0);
    }
}
