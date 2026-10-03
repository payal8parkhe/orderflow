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
public class ProductEventPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventType; // PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_DELETED, VARIANT_CREATED
    private Long productId;
    private Long variantId;
    private String sku;
    private String name;
    private String category;
    private BigDecimal price;
    private Long sellerId;
    private Integer initialStock;
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
