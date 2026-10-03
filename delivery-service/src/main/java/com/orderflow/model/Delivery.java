package com.orderflow.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "deliveries", indexes = {
        @Index(name = "idx_delivery_order", columnList = "order_id", unique = true),
        @Index(name = "idx_delivery_agent", columnList = "delivery_agent_id"),
        @Index(name = "idx_delivery_tracking", columnList = "tracking_number", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "order_number", length = 64)
    private String orderNumber;

    @Column(name = "delivery_agent_id", nullable = false)
    private Long deliveryAgentId;

    @Column(name = "delivery_agent_name", length = 150)
    private String deliveryAgentName;

    @Column(name = "delivery_agent_email", length = 100)
    private String deliveryAgentEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeliveryStatus status;

    @Column(name = "tracking_number", nullable = false, unique = true, length = 64)
    private String trackingNumber;

    private LocalDateTime assignedAt;

    private LocalDateTime pickedUpAt;

    private LocalDateTime deliveredAt;

    @Column(name = "recipient_proof_name", length = 150)
    private String recipientProofName;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Embedded
    private OrderAddress shippingAddress;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
