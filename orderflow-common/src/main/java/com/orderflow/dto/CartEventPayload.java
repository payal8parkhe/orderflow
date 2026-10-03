package com.orderflow.dto;

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
public class CartEventPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventType; // ITEM_ADDED, ITEM_REMOVED, SAVED_FOR_LATER, MOVED_TO_CART, CART_MERGED, CHECKOUT_INITIATED
    private Long userId;
    private String userEmail;
    private String guestSessionId;
    private Long productId;
    private String productSku;
    private Integer quantity;
    private BigDecimal price;
    private LocalDateTime timestamp;
}
