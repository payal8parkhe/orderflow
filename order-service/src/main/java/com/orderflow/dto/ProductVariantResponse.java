package com.orderflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductVariantResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long productId;
    private String sku;
    private String title;
    private BigDecimal price;
    private Boolean isDefault;
    private Boolean active;
    private String imageUrl;
    private Integer availableStock;
    private Integer reservedStock;
    private Map<String, String> attributes;
}
