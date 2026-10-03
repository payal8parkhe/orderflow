package com.orderflow.controller;

import com.orderflow.dto.NotificationResponse;
import com.orderflow.service.NotificationService;
import com.orderflow.service.SseNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Event-driven customer notification and alert dispatch APIs")
public class NotificationController {

    private final NotificationService notificationService;
    private final SseNotificationService sseNotificationService;

    @Operation(summary = "Subscribe to live notification stream (SSE)")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) String email) {
        String targetEmail = null;
        if (userDetails != null && StringUtils.hasText(userDetails.getUsername())) {
            targetEmail = userDetails.getUsername();
        } else if (StringUtils.hasText(email)) {
            targetEmail = email;
        }

        if (!StringUtils.hasText(targetEmail)) {
            targetEmail = "guest@orderflow.com";
        }

        return sseNotificationService.subscribe(targetEmail);
    }

    @Operation(summary = "Get user notifications")
    @GetMapping
    public ResponseEntity<List<NotificationResponse>> getNotifications(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(notificationService.getNotificationsForUser(userDetails.getUsername()));
    }

    @Operation(summary = "Get unread notification count")
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.ok(Map.of("unreadCount", 0L));
        }
        long count = notificationService.getUnreadCount(userDetails.getUsername());
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    @Operation(summary = "Mark single notification as read")
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        if (userDetails != null) {
            notificationService.markAsRead(userDetails.getUsername(), id);
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark all notifications as read")
    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails != null) {
            notificationService.markAllAsRead(userDetails.getUsername());
        }
        return ResponseEntity.noContent().build();
    }
}
