package com.orderflow.controller;

import com.orderflow.dto.CartItemRequest;
import com.orderflow.dto.CartResponse;
import com.orderflow.dto.OrderResponse;
import com.orderflow.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@Tag(name = "Shopping Cart", description = "Amazon-grade two-tiered persistent cart, Saved-For-Later, guest merging, and checkout")
public class CartController {

    private final CartService cartService;

    private String getUsername(UserDetails userDetails) {
        return userDetails != null ? userDetails.getUsername() : null;
    }

    @Operation(summary = "Get cart", description = "Retrieve active and saved-for-later cart items with dynamic pricing and stock warnings")
    @GetMapping
    public ResponseEntity<CartResponse> getCart(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader(value = "X-Guest-Session-ID", required = false) String guestSessionId,
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        if (refresh) {
            cartService.evictCache(getUsername(userDetails), guestSessionId);
        }
        return ResponseEntity.ok(cartService.getCart(getUsername(userDetails), guestSessionId));
    }

    @Operation(summary = "Add item to cart", description = "Add product to active shopping cart for authenticated user or guest session")
    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader(value = "X-Guest-Session-ID", required = false) String guestSessionId,
            @Valid @RequestBody CartItemRequest request) {
        return ResponseEntity.ok(cartService.addItem(getUsername(userDetails), guestSessionId, request));
    }

    @Operation(summary = "Update item quantity", description = "Adjust quantity of an active cart item")
    @PutMapping("/items/{productId}")
    public ResponseEntity<CartResponse> updateItemQuantity(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader(value = "X-Guest-Session-ID", required = false) String guestSessionId,
            @PathVariable Long productId,
            @RequestParam(name = "quantity", required = false) Integer quantity,
            @RequestBody(required = false) java.util.Map<String, Object> body) {
        Integer resolvedQty = quantity;
        if (resolvedQty == null && body != null && body.containsKey("quantity")) {
            Object q = body.get("quantity");
            if (q instanceof Number) {
                resolvedQty = ((Number) q).intValue();
            } else if (q != null) {
                try {
                    resolvedQty = Integer.parseInt(q.toString());
                } catch (NumberFormatException ignored) {}
            }
        }
        return ResponseEntity.ok(cartService.updateItemQuantity(getUsername(userDetails), guestSessionId, productId, resolvedQty));
    }

    @Operation(summary = "Save item for later", description = "Move item from active shopping cart to Saved-For-Later wishlist")
    @PutMapping("/items/{productId}/save-for-later")
    public ResponseEntity<CartResponse> saveForLater(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader(value = "X-Guest-Session-ID", required = false) String guestSessionId,
            @PathVariable Long productId) {
        return ResponseEntity.ok(cartService.saveForLater(getUsername(userDetails), guestSessionId, productId));
    }

    @Operation(summary = "Move item to active cart", description = "Move item from Saved-For-Later wishlist back into active shopping cart")
    @PutMapping("/items/{productId}/move-to-cart")
    public ResponseEntity<CartResponse> moveToCart(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader(value = "X-Guest-Session-ID", required = false) String guestSessionId,
            @PathVariable Long productId) {
        return ResponseEntity.ok(cartService.moveToCart(getUsername(userDetails), guestSessionId, productId));
    }

    @Operation(summary = "Remove item", description = "Remove product completely from either active cart or saved-for-later")
    @DeleteMapping("/items/{productId}")
    public ResponseEntity<CartResponse> removeItem(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader(value = "X-Guest-Session-ID", required = false) String guestSessionId,
            @PathVariable Long productId) {
        return ResponseEntity.ok(cartService.removeItem(getUsername(userDetails), guestSessionId, productId));
    }

    @Operation(summary = "Clear active cart", description = "Remove all active items from the cart")
    @DeleteMapping
    public ResponseEntity<Void> clearCart(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader(value = "X-Guest-Session-ID", required = false) String guestSessionId) {
        cartService.clearCart(getUsername(userDetails), guestSessionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Merge guest cart into user account", description = "Consolidate anonymous guest session items into the authenticated user account upon sign in")
    @PostMapping("/merge")
    public ResponseEntity<CartResponse> mergeGuestCart(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(name = "guestSessionId", required = false) String guestSessionIdParam,
            @RequestHeader(value = "X-Guest-Session-ID", required = false) String guestSessionIdHeader) {
        String guestSessionId = (guestSessionIdParam != null && !guestSessionIdParam.isBlank())
                ? guestSessionIdParam : guestSessionIdHeader;
        return ResponseEntity.ok(cartService.mergeGuestCart(userDetails.getUsername(), guestSessionId));
    }

    @Operation(summary = "Checkout active cart", description = "Atomically converts all in-stock active items into a PostgreSQL order, holds stock with pessimistic lock, and broadcasts Kafka event")
    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(@AuthenticationPrincipal UserDetails userDetails) {
        OrderResponse orderResponse = cartService.checkout(userDetails.getUsername());
        return new ResponseEntity<>(orderResponse, HttpStatus.CREATED);
    }
}
