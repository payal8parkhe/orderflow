package com.orderflow.dto;

import com.orderflow.model.DeliveryStatus;
import com.orderflow.model.OrderAddress;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryResponse {

    private Long id;
    private Long orderId;
    private String orderNumber;
    private Long deliveryAgentId;
    private String deliveryAgentName;
    private DeliveryStatus status;
    private String trackingNumber;
    private LocalDateTime assignedAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime deliveredAt;
    private String recipientProofName;
    private String failureReason;
    private int attemptCount;
    private OrderAddress shippingAddress;
    private LocalDateTime createdAt;
}
