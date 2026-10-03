package com.orderflow.kafka;

import com.orderflow.dto.PaymentEventPayload;
import com.orderflow.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentSagaCoordinator {

    private final OrderService orderService;

    @KafkaListener(topics = "payment-events", groupId = "orderflow-payment-saga-group")
    public void handlePaymentEvent(PaymentEventPayload event) {
        log.info("Payment Saga Coordinator received event: [{}] for Order #{} (Payment #{})",
                event.getEventType(), event.getOrderNumber(), event.getPaymentId());

        try {
            if ("PAYMENT_COMPLETED".equals(event.getEventType()) || "PAYMENT_COD_CONFIRMED".equals(event.getEventType())) {
                orderService.confirmPayment(event.getOrderId(), event.getPaymentId());
            } else if ("PAYMENT_FAILED".equals(event.getEventType())) {
                orderService.handlePaymentFailure(event.getOrderId(), event.getFailureReason());
            }
        } catch (com.orderflow.exception.BadRequestException e) {
            log.info("Idempotent event handled safely: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error coordinating payment saga for Order #{}: {}. Triggering Kafka retry / DLT recovery.",
                    event.getOrderNumber(), e.getMessage(), e);
            throw e;
        }
    }
}
