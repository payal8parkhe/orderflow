package com.orderflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String sku;
    private String name;
    private String description;
    private BigDecimal price;
    private String category;
    private String imageUrl;
    private Long sellerId;
    private Integer availableStock;
    private List<ProductVariantResponse> variants;
}
