package com.orderflow.dto;

import com.orderflow.model.CartItemStatus;
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
public class CartItemResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long productId;
    private String productName;
    private String productSku;
    private Long variantId;
    private String variantTitle;
    private String variantSku;
    private String imageUrl;
    private Map<String, String> variantAttributes;
    private BigDecimal price;              // Current live catalog price
    private BigDecimal priceWhenAdded;     // Price at the moment it was added
    private BigDecimal priceDifference;    // Current - priceWhenAdded
    private String priceAlert;             // e.g. "Price dropped by ₹15.00!"
    private Integer quantity;
    private BigDecimal subtotal;
    private Integer availableStock;
    private boolean inStock;
    private String stockWarning;           // e.g. "Only 2 left in stock" or "Out of Stock"
    private CartItemStatus status;         // ACTIVE or SAVED_FOR_LATER
}
