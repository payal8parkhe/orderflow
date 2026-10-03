package com.orderflow.dto;

import com.orderflow.model.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEventPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventType; // PAYMENT_COMPLETED, PAYMENT_FAILED, PAYMENT_REFUNDED
    private Long paymentId;
    private Long orderId;
    private String orderNumber;
    private Long userId;
    private String userEmail;
    private BigDecimal amount;
    private PaymentStatus status;
    private String failureReason;
    private String refundReference;
    private String transactionReference;
    private LocalDateTime timestamp;
}
