package com.orderflow.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayOrderRequest {
    @NotNull(message = "Order ID is required")
    private Long orderId;
    private String currency;
}
