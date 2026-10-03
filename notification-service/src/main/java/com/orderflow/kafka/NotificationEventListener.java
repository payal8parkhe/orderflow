package com.orderflow.kafka;

import com.orderflow.dto.DeliveryEventPayload;
import com.orderflow.dto.OrderEventPayload;
import com.orderflow.dto.PaymentEventPayload;
import com.orderflow.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

    private final NotificationService notificationService;

    @KafkaListener(topics = "order-events", groupId = "notification-service-order-group")
    public void handleOrderEvent(OrderEventPayload payload) {
        log.info("notification-service consumed Order Event [{}] for Order #{}", payload.getEventType(), payload.getOrderNumber());

        if ("ORDER_CREATED".equalsIgnoreCase(payload.getEventType())) {
            notificationService.createNotification(
                    payload.getUserId(),
                    payload.getUserEmail(),
                    payload.getOrderId(),
                    "Order Placed Successfully",
                    "Your order #" + payload.getOrderNumber() + " totaling ₹" + payload.getTotalAmount() + " has been placed and is awaiting payment.",
                    "ORDER_CREATED"
            );
        } else if ("ORDER_STATUS_CHANGED".equalsIgnoreCase(payload.getEventType())) {
            notificationService.createNotification(
                    payload.getUserId(),
                    payload.getUserEmail(),
                    payload.getOrderId(),
                    "Order Status: " + payload.getStatus(),
                    "Your order #" + payload.getOrderNumber() + " status is now " + payload.getStatus() + ".",
                    "ORDER_STATUS_CHANGED"
            );
        }
    }

    @KafkaListener(topics = "payment-events", groupId = "notification-service-payment-group")
    public void handlePaymentEvent(PaymentEventPayload payload) {
        log.info("notification-service consumed Payment Event [{}] for Order #{}", payload.getEventType(), payload.getOrderNumber());

        if ("PAYMENT_COMPLETED".equalsIgnoreCase(payload.getEventType())) {
            notificationService.createNotification(
                    payload.getUserId(),
                    payload.getUserEmail(),
                    payload.getOrderId(),
                    "Payment Successful",
                    "Payment of ₹" + payload.getAmount() + " for order #" + payload.getOrderNumber() + " was successfully confirmed.",
                    "PAYMENT_COMPLETED"
            );
        } else if ("PAYMENT_FAILED".equalsIgnoreCase(payload.getEventType())) {
            notificationService.createNotification(
                    payload.getUserId(),
                    payload.getUserEmail(),
                    payload.getOrderId(),
                    "Payment Failed",
                    "Payment for order #" + payload.getOrderNumber() + " failed: " + (payload.getFailureReason() != null ? payload.getFailureReason() : "Transaction declined"),
                    "PAYMENT_FAILED"
            );
        } else if ("PAYMENT_REFUNDED".equalsIgnoreCase(payload.getEventType())) {
            notificationService.createNotification(
                    payload.getUserId(),
                    payload.getUserEmail(),
                    payload.getOrderId(),
                    "Payment Refunded",
                    "Refund of ₹" + payload.getAmount() + " for order #" + payload.getOrderNumber() + " has been initiated.",
                    "PAYMENT_REFUNDED"
            );
        }
    }

    @KafkaListener(topics = "delivery-events", groupId = "notification-service-delivery-group")
    public void handleDeliveryEvent(DeliveryEventPayload payload) {
        log.info("notification-service consumed Delivery Event [{}] for Order #{}", payload.getEventType(), payload.getOrderNumber());

        String eventType = payload.getEventType() != null ? payload.getEventType() : "";
        String title;
        String message;

        if (eventType.contains("ASSIGNED")) {
            title = "Delivery Agent Assigned";
            message = "Order #" + payload.getOrderNumber() + " has been assigned to courier " +
                    (payload.getDeliveryAgentName() != null ? payload.getDeliveryAgentName() : "") +
                    " (Tracking: " + payload.getTrackingNumber() + ").";
        } else if (eventType.contains("PICKED_UP")) {
            title = "Order Picked Up";
            message = "Order #" + payload.getOrderNumber() + " has been picked up by the courier.";
        } else if (eventType.contains("OUT_FOR_DELIVERY")) {
            title = "Out for Delivery";
            message = "Order #" + payload.getOrderNumber() + " is out for delivery! Tracking: " + payload.getTrackingNumber();
        } else if (eventType.contains("DELIVERED") || eventType.contains("COMPLETED")) {
            title = "Order Delivered";
            message = "Order #" + payload.getOrderNumber() + " has been successfully delivered! Received by: " +
                    (payload.getRecipientProofName() != null ? payload.getRecipientProofName() : "Customer");
        } else if (eventType.contains("FAILED")) {
            title = "Delivery Attempt Failed";
            message = "Delivery attempt for Order #" + payload.getOrderNumber() + " was unsuccessful: " +
                    (payload.getFailureReason() != null ? payload.getFailureReason() : "Customer unavailable");
        } else {
            title = "Delivery Update";
            message = "Delivery status for Order #" + payload.getOrderNumber() + " is now " + payload.getStatus();
        }

        // Email can default to guest or order reference
        String userEmail = "order-" + payload.getOrderId() + "@orderflow.com";
        notificationService.createNotification(
                null,
                userEmail,
                payload.getOrderId(),
                title,
                message,
                eventType
        );
    }
}
