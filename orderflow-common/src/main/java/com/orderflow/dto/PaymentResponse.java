package com.orderflow.dto;

import com.orderflow.model.PaymentMethod;
import com.orderflow.model.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private Long id;
    private Long orderId;
    private String orderNumber;
    private PaymentMethod paymentMethod;
    private PaymentStatus status;
    private String transactionReference;
    private BigDecimal amount;
    private String currency;
    private String refundReference;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}
