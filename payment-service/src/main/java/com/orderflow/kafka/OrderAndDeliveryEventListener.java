package com.orderflow.kafka;

import com.orderflow.dto.DeliveryEventPayload;
import com.orderflow.dto.OrderEventPayload;
import com.orderflow.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderAndDeliveryEventListener {

    private final PaymentService paymentService;

    @KafkaListener(topics = "order-events", groupId = "payment-service-order-group")
    public void handleOrderEvent(OrderEventPayload payload) {
        log.info("payment-service consumed Order Event [{}] for Order #{}", payload.getEventType(), payload.getOrderNumber());

        if ("ORDER_CREATED".equals(payload.getEventType())) {
            paymentService.recordPendingOrder(
                    payload.getOrderId(),
                    payload.getOrderNumber(),
                    payload.getUserId(),
                    payload.getUserEmail(),
                    payload.getTotalAmount()
            );
        } else if ("ORDER_CANCELLED".equals(payload.getEventType())) {
            log.info("Triggering automatic refund saga for cancelled Order #{}", payload.getOrderNumber());
            paymentService.refundPaymentIfPresent(payload.getOrderId());
        }
    }

    @KafkaListener(topics = "delivery-events", groupId = "payment-service-delivery-group")
    public void handleDeliveryEvent(DeliveryEventPayload payload) {
        log.info("payment-service consumed Delivery Event [{}] for Order #{}", payload.getEventType(), payload.getOrderNumber());

        if ("DELIVERY_DELIVERED".equals(payload.getEventType())) {
            log.info("Doorstep delivery completed. Settling Cash on Delivery if pending for Order #{}", payload.getOrderId());
            paymentService.completeCodPaymentIfPending(payload.getOrderId());
        }
    }
}
