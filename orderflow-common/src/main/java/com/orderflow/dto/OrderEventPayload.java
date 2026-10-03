package com.orderflow.dto;

import com.orderflow.model.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderEventPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventType; // e.g., ORDER_CREATED, ORDER_STATUS_CHANGED
    private Long orderId;
    private String orderNumber;
    private Long userId;
    private String userEmail;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private com.orderflow.model.OrderAddress shippingAddress;
    private LocalDateTime timestamp;
}
