package com.orderflow.dto;

import com.orderflow.model.DeliveryStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryEventPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventType; // DELIVERY_ASSIGNED, DELIVERY_PICKED_UP, DELIVERY_OUT_FOR_DELIVERY, DELIVERY_COMPLETED, DELIVERY_FAILED
    private Long deliveryId;
    private Long orderId;
    private String orderNumber;
    private Long deliveryAgentId;
    private String deliveryAgentName;
    private DeliveryStatus status;
    private String trackingNumber;
    private String recipientProofName;
    private String failureReason;
    private LocalDateTime timestamp;
}
