package com.orderflow.service;

import com.orderflow.dto.NotificationResponse;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.model.Notification;
import com.orderflow.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final SseNotificationService sseNotificationService;

    @Transactional
    public Notification createNotification(Long userId, String userEmail, Long orderId, String title, String message, String type) {
        if (userEmail == null || userEmail.isBlank()) {
            userEmail = "user-" + (userId != null ? userId : "guest") + "@orderflow.com";
        }

        Notification notification = Notification.builder()
                .userId(userId)
                .userEmail(userEmail)
                .orderId(orderId)
                .title(title)
                .message(message)
                .type(type)
                .isRead(false)
                .build();

        Notification saved = notificationRepository.save(notification);

        // Transactional Email / Push Dispatch Simulation
        log.info("\n======================================================\n" +
                 "[NOTIFICATION DISPATCH - notification-service]\n" +
                 "  Recipient Email:   {}\n" +
                 "  User ID:           {}\n" +
                 "  Event Type:        {}\n" +
                 "  Subject:           {}\n" +
                 "  Order Reference:   #{}\n" +
                 "  Message:           {}\n" +
                 "======================================================",
                userEmail, userId != null ? userId : "N/A", type, title, orderId != null ? orderId : "N/A", message);

        // Real-time Push to live connected clients via SSE
        try {
            sseNotificationService.sendNotification(userEmail, saved);
        } catch (Exception ex) {
            log.error("Failed to push real-time notification to user {}: {}", userEmail, ex.getMessage());
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotificationsForUser(String userEmail) {
        return notificationRepository.findByUserEmailOrderByCreatedAtDesc(userEmail)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(String userEmail) {
        return notificationRepository.countByUserEmailAndIsReadFalse(userEmail);
    }

    @Transactional
    public void markAsRead(String userEmail, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found with id: " + notificationId));

        if (notification.getUserEmail().equalsIgnoreCase(userEmail)) {
            notification.setIsRead(true);
            notificationRepository.save(notification);
        }
    }

    @Transactional
    public void markAllAsRead(String userEmail) {
        List<Notification> notifications = notificationRepository.findByUserEmailOrderByCreatedAtDesc(userEmail);
        for (Notification n : notifications) {
            if (!n.getIsRead()) {
                n.setIsRead(true);
            }
        }
        notificationRepository.saveAll(notifications);
    }

    private NotificationResponse mapToResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .userId(n.getUserId())
                .userEmail(n.getUserEmail())
                .orderId(n.getOrderId())
                .title(n.getTitle())
                .message(n.getMessage())
                .type(n.getType())
                .isRead(n.getIsRead())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
