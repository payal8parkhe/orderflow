package com.orderflow.service;

import com.orderflow.dto.DeliveryEventPayload;
import com.orderflow.dto.NotificationResponse;
import com.orderflow.dto.OrderEventPayload;
import com.orderflow.dto.PaymentEventPayload;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.kafka.NotificationEventListener;
import com.orderflow.model.Notification;
import com.orderflow.model.OrderStatus;
import com.orderflow.model.PaymentStatus;
import com.orderflow.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private SseNotificationService sseNotificationService;

    @InjectMocks
    private NotificationService notificationService;

    private Notification sampleNotification;

    @BeforeEach
    void setUp() {
        sampleNotification = Notification.builder()
                .id(1L)
                .userId(100L)
                .userEmail("customer@orderflow.com")
                .orderId(500L)
                .title("Order Placed")
                .message("Your order #ORD-500 has been placed.")
                .type("ORDER_CREATED")
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("Notification Creation & Persistence")
    class CreationTests {

        @Test
        @DisplayName("createNotification successfully saves and returns Notification entity")
        void createNotification_success() {
            when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

            Notification created = notificationService.createNotification(
                    100L,
                    "customer@orderflow.com",
                    500L,
                    "Order Placed",
                    "Your order has been placed",
                    "ORDER_CREATED"
            );

            assertThat(created).isNotNull();
            assertThat(created.getUserEmail()).isEqualTo("customer@orderflow.com");
            assertThat(created.getOrderId()).isEqualTo(500L);
            assertThat(created.getIsRead()).isFalse();

            verify(notificationRepository).save(any(Notification.class));
        }

        @Test
        @DisplayName("createNotification provides fallback email if userEmail is null")
        void createNotification_fallbackEmail() {
            when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

            Notification created = notificationService.createNotification(
                    42L,
                    null,
                    600L,
                    "Order Placed",
                    "Your order has been placed",
                    "ORDER_CREATED"
            );

            assertThat(created.getUserEmail()).isEqualTo("user-42@orderflow.com");
        }
    }

    @Nested
    @DisplayName("Query & Read Status Operations")
    class ReadStatusTests {

        @Test
        @DisplayName("getNotificationsForUser returns mapped response DTOs")
        void getNotificationsForUser_success() {
            when(notificationRepository.findByUserEmailOrderByCreatedAtDesc("customer@orderflow.com"))
                    .thenReturn(List.of(sampleNotification));

            List<NotificationResponse> responses = notificationService.getNotificationsForUser("customer@orderflow.com");

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getId()).isEqualTo(1L);
            assertThat(responses.get(0).getUserEmail()).isEqualTo("customer@orderflow.com");
            assertThat(responses.get(0).getTitle()).isEqualTo("Order Placed");
            assertThat(responses.get(0).getIsRead()).isFalse();
        }

        @Test
        @DisplayName("getUnreadCount returns correct count")
        void getUnreadCount_success() {
            when(notificationRepository.countByUserEmailAndIsReadFalse("customer@orderflow.com"))
                    .thenReturn(3L);

            long count = notificationService.getUnreadCount("customer@orderflow.com");
            assertThat(count).isEqualTo(3L);
        }

        @Test
        @DisplayName("markAsRead marks notification as read when email matches")
        void markAsRead_matchesEmail() {
            when(notificationRepository.findById(1L)).thenReturn(Optional.of(sampleNotification));
            when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

            notificationService.markAsRead("customer@orderflow.com", 1L);

            assertThat(sampleNotification.getIsRead()).isTrue();
            verify(notificationRepository).save(sampleNotification);
        }

        @Test
        @DisplayName("markAsRead does not mark as read if email does not match")
        void markAsRead_differentUser() {
            when(notificationRepository.findById(1L)).thenReturn(Optional.of(sampleNotification));

            notificationService.markAsRead("other@orderflow.com", 1L);

            assertThat(sampleNotification.getIsRead()).isFalse();
            verify(notificationRepository, never()).save(sampleNotification);
        }

        @Test
        @DisplayName("markAsRead throws ResourceNotFoundException if notification missing")
        void markAsRead_notFound() {
            when(notificationRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notificationService.markAsRead("customer@orderflow.com", 999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("markAllAsRead sets all unread notifications to read")
        void markAllAsRead_success() {
            Notification secondNotification = Notification.builder()
                    .id(2L)
                    .userId(100L)
                    .userEmail("customer@orderflow.com")
                    .title("Payment Confirmed")
                    .message("Payment of ₹4,999 confirmed")
                    .type("PAYMENT_COMPLETED")
                    .isRead(false)
                    .build();

            when(notificationRepository.findByUserEmailOrderByCreatedAtDesc("customer@orderflow.com"))
                    .thenReturn(List.of(sampleNotification, secondNotification));

            notificationService.markAllAsRead("customer@orderflow.com");

            assertThat(sampleNotification.getIsRead()).isTrue();
            assertThat(secondNotification.getIsRead()).isTrue();
            verify(notificationRepository).saveAll(any());
        }
    }

    @Nested
    @DisplayName("Kafka Event Consumer Handling")
    class KafkaConsumerTests {

        @Test
        @DisplayName("handleOrderEvent processes ORDER_CREATED correctly")
        void handleOrderEvent_orderCreated() {
            NotificationEventListener listener = new NotificationEventListener(notificationService);
            when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

            OrderEventPayload payload = OrderEventPayload.builder()
                    .eventType("ORDER_CREATED")
                    .orderId(123L)
                    .orderNumber("ORD-123")
                    .userId(55L)
                    .userEmail("buyer@orderflow.com")
                    .totalAmount(new BigDecimal("4999.00"))
                    .status(OrderStatus.AWAITING_PAYMENT)
                    .build();

            listener.handleOrderEvent(payload);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());

            Notification saved = captor.getValue();
            assertThat(saved.getUserEmail()).isEqualTo("buyer@orderflow.com");
            assertThat(saved.getOrderId()).isEqualTo(123L);
            assertThat(saved.getType()).isEqualTo("ORDER_CREATED");
            assertThat(saved.getTitle()).isEqualTo("Order Placed Successfully");
            assertThat(saved.getMessage()).contains("ORD-123");
        }

        @Test
        @DisplayName("handlePaymentEvent processes PAYMENT_COMPLETED correctly")
        void handlePaymentEvent_completed() {
            NotificationEventListener listener = new NotificationEventListener(notificationService);
            when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

            PaymentEventPayload payload = PaymentEventPayload.builder()
                    .eventType("PAYMENT_COMPLETED")
                    .paymentId(10L)
                    .orderId(123L)
                    .orderNumber("ORD-123")
                    .userId(55L)
                    .userEmail("buyer@orderflow.com")
                    .amount(new BigDecimal("4999.00"))
                    .status(PaymentStatus.COMPLETED)
                    .build();

            listener.handlePaymentEvent(payload);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());

            Notification saved = captor.getValue();
            assertThat(saved.getType()).isEqualTo("PAYMENT_COMPLETED");
            assertThat(saved.getTitle()).isEqualTo("Payment Successful");
            assertThat(saved.getMessage()).contains("₹4999.00");
        }

        @Test
        @DisplayName("handleDeliveryEvent processes DELIVERY_OUT_FOR_DELIVERY correctly")
        void handleDeliveryEvent_outForDelivery() {
            NotificationEventListener listener = new NotificationEventListener(notificationService);
            when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

            DeliveryEventPayload payload = DeliveryEventPayload.builder()
                    .eventType("DELIVERY_OUT_FOR_DELIVERY")
                    .deliveryId(88L)
                    .orderId(123L)
                    .orderNumber("ORD-123")
                    .trackingNumber("TRK-ABC123XYZ")
                    .build();

            listener.handleDeliveryEvent(payload);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());

            Notification saved = captor.getValue();
            assertThat(saved.getType()).isEqualTo("DELIVERY_OUT_FOR_DELIVERY");
            assertThat(saved.getTitle()).isEqualTo("Out for Delivery");
            assertThat(saved.getMessage()).contains("TRK-ABC123XYZ");
        }
    }
}
