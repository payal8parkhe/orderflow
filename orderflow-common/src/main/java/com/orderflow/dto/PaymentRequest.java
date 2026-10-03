package com.orderflow.dto;

import com.orderflow.model.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {

    @NotNull(message = "Order ID is required")
    private Long orderId;

    @NotNull(message = "Payment method is required")
    private PaymentMethod paymentMethod;

    // Optional simulation flag for testing payment failure & compensating transaction
    private Boolean simulateFailure;

    // Optional amount sent by client to verify price integrity against the order
    private java.math.BigDecimal amount;
}
