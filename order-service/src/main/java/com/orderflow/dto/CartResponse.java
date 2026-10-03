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
@NoArgsConstructor
@AllArgsConstructor
public class CartResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    // Active items ready for checkout
    private List<CartItemResponse> items;
    private Integer totalItems;
    private BigDecimal totalAmount;

    // Amazon-style Saved for Later items (Wishlist / Parking lot)
    private List<CartItemResponse> savedItems;
    private Integer savedItemsCount;
}
