package com.orderflow.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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
public class ProductVariantRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "Variant SKU is required")
    private String sku;

    @NotBlank(message = "Variant title is required")
    private String title;

    @DecimalMin(value = "0.01", message = "Price must be greater than 0")
    private BigDecimal price;

    private Integer initialStock;

    private Boolean isDefault;

    private String imageUrl;

    private Map<String, String> attributes;
}
