package com.orderflow.service;

import com.orderflow.dto.DeliveryAssignRequest;
import com.orderflow.dto.DeliveryEventPayload;
import com.orderflow.dto.DeliveryResponse;
import com.orderflow.dto.DeliveryStatusUpdateRequest;
import com.orderflow.exception.BadRequestException;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.kafka.DeliveryEventProducer;
import com.orderflow.model.Delivery;
import com.orderflow.model.DeliveryStatus;
import com.orderflow.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryEventProducer deliveryEventProducer;

    @Transactional
    public DeliveryResponse assignDelivery(DeliveryAssignRequest request) {
        Delivery delivery = deliveryRepository.findByOrderId(request.getOrderId()).orElseGet(() ->
                Delivery.builder()
                        .orderId(request.getOrderId())
                        .orderNumber("ORD-" + request.getOrderId())
                        .trackingNumber("TRK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase())
                        .build()
        );

        delivery.setDeliveryAgentId(request.getDeliveryAgentId());
        if (delivery.getDeliveryAgentName() == null) {
            delivery.setDeliveryAgentName("Agent #" + request.getDeliveryAgentId());
        }
        if (delivery.getTrackingNumber() == null) {
            delivery.setTrackingNumber("TRK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase());
        }
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery.setAssignedAt(LocalDateTime.now());
        Delivery saved = deliveryRepository.save(delivery);

        deliveryEventProducer.sendDeliveryEvent(DeliveryEventPayload.builder()
                .eventType("DELIVERY_ASSIGNED")
                .deliveryId(saved.getId())
                .orderId(saved.getOrderId())
                .orderNumber(saved.getOrderNumber())
                .deliveryAgentId(saved.getDeliveryAgentId())
                .deliveryAgentName(saved.getDeliveryAgentName())
                .status(saved.getStatus())
                .trackingNumber(saved.getTrackingNumber())
                .timestamp(LocalDateTime.now())
                .build());

        log.info("Order #{} assigned to delivery agent ID {} (Tracking: {})",
                saved.getOrderNumber(), saved.getDeliveryAgentId(), saved.getTrackingNumber());

        return mapToResponse(saved);
    }

    @Transactional
    public DeliveryResponse updateDeliveryStatus(String agentEmail, Long deliveryId, DeliveryStatusUpdateRequest request) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found with id: " + deliveryId));

        DeliveryStatus newStatus = request.getStatus();
        delivery.setStatus(newStatus);

        if (agentEmail != null && (delivery.getDeliveryAgentEmail() == null || delivery.getDeliveryAgentEmail().isBlank())) {
            delivery.setDeliveryAgentEmail(agentEmail);
            delivery.setDeliveryAgentName(agentEmail.split("@")[0]);
        }

        if (newStatus == DeliveryStatus.PICKED_UP) {
            delivery.setPickedUpAt(LocalDateTime.now());
        } else if (newStatus == DeliveryStatus.OUT_FOR_DELIVERY) {
            if (delivery.getPickedUpAt() == null) {
                delivery.setPickedUpAt(LocalDateTime.now());
            }
        } else if (newStatus == DeliveryStatus.DELIVERED) {
            delivery.setDeliveredAt(LocalDateTime.now());
            if (request.getRecipientProofName() != null && !request.getRecipientProofName().isBlank()) {
                delivery.setRecipientProofName(request.getRecipientProofName());
            }
        } else if (newStatus == DeliveryStatus.FAILED_ATTEMPT) {
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            delivery.setFailureReason(request.getFailureReason() != null ? request.getFailureReason() : "Customer unavailable");
        }

        Delivery saved = deliveryRepository.save(delivery);

        // Publish delivery event (triggers saga updates in order-service & payment-service)
        String eventType = "DELIVERY_" + newStatus.name();
        deliveryEventProducer.sendDeliveryEvent(DeliveryEventPayload.builder()
                .eventType(eventType)
                .deliveryId(saved.getId())
                .orderId(saved.getOrderId())
                .orderNumber(saved.getOrderNumber())
                .deliveryAgentId(saved.getDeliveryAgentId())
                .deliveryAgentName(saved.getDeliveryAgentName())
                .status(saved.getStatus())
                .trackingNumber(saved.getTrackingNumber())
                .recipientProofName(saved.getRecipientProofName())
                .failureReason(saved.getFailureReason())
                .timestamp(LocalDateTime.now())
                .build());

        log.info("Delivery #{} status updated to {} (Tracking: {})", saved.getId(), newStatus, saved.getTrackingNumber());

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<DeliveryResponse> getMyAssignedDeliveries(String agentEmail) {
        return deliveryRepository.findByDeliveryAgentEmailOrderByAssignedAtDesc(agentEmail)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeliveryResponse getDeliveryByOrderId(Long orderId) {
        Delivery delivery = deliveryRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("No delivery found for order id: " + orderId));
        return mapToResponse(delivery);
    }

    private DeliveryResponse mapToResponse(Delivery d) {
        return DeliveryResponse.builder()
                .id(d.getId())
                .orderId(d.getOrderId())
                .orderNumber(d.getOrderNumber())
                .deliveryAgentId(d.getDeliveryAgentId())
                .deliveryAgentName(d.getDeliveryAgentName())
                .status(d.getStatus())
                .trackingNumber(d.getTrackingNumber())
                .assignedAt(d.getAssignedAt())
                .pickedUpAt(d.getPickedUpAt())
                .deliveredAt(d.getDeliveredAt())
                .recipientProofName(d.getRecipientProofName())
                .failureReason(d.getFailureReason())
                .attemptCount(d.getAttemptCount())
                .shippingAddress(d.getShippingAddress())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
