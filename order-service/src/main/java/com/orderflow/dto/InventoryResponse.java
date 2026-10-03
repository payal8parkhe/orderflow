package com.orderflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InventoryResponse {
    private Long id;
    private Long productId;
    private String productSku;
    private String productName;
    private Long variantId;
    private String variantSku;
    private String variantTitle;
    private Integer availableStock;
    private Integer reservedStock;
    private Long version;
}
