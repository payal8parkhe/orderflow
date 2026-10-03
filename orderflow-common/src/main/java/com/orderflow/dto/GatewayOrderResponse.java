package com.orderflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayOrderResponse {
    private String gatewayOrderId;
    private Long orderId;
    private String orderNumber;
    private BigDecimal amount;
    private Long amountInSubunits; // e.g. amount in paise or cents
    private String currency;
    private String keyId;
}
