package com.orderflow.dto;

import com.orderflow.model.OrderStatus;
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
public class SellerOrderResponse {
    private Long orderId;
    private String orderNumber;
    private String customerEmail;
    private Long productId;
    private String productName;
    private String sku;
    private String variantTitle;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
    private OrderStatus orderStatus;
    private LocalDateTime orderDate;
}
