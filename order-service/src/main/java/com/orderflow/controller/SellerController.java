package com.orderflow.controller;

import com.orderflow.dto.ProductRequest;
import com.orderflow.dto.ProductResponse;
import com.orderflow.dto.SellerOrderResponse;
import com.orderflow.dto.SellerProfileRequest;
import com.orderflow.model.Seller;
import com.orderflow.service.SellerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/seller")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
@Tag(name = "Seller & Vendor Operations", description = "Endpoints for merchant onboarding, catalog management, and seller order fulfillment")
public class SellerController {

    private final SellerService sellerService;

    @Operation(summary = "Get current seller profile")
    @GetMapping("/profile")
    public ResponseEntity<Seller> getSellerProfile(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(sellerService.getSellerProfileByEmail(userDetails.getUsername()));
    }

    @Operation(summary = "Update seller profile details")
    @PutMapping("/profile")
    public ResponseEntity<Seller> updateSellerProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody SellerProfileRequest request) {
        return ResponseEntity.ok(sellerService.updateSellerProfileByEmail(userDetails.getUsername(), request));
    }

    @Operation(summary = "Get products listed by current seller")
    @GetMapping("/products")
    public ResponseEntity<List<ProductResponse>> getSellerProducts(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(sellerService.getSellerProductsByEmail(userDetails.getUsername()));
    }

    @Operation(summary = "Create a new product under seller catalog")
    @PostMapping("/products")
    public ResponseEntity<ProductResponse> createSellerProduct(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ProductRequest request) {
        ProductResponse response = sellerService.createProductForSellerByEmail(userDetails.getUsername(), request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @Operation(summary = "Update product owned by seller")
    @PutMapping("/products/{id}")
    public ResponseEntity<ProductResponse> updateSellerProduct(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id,
            @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(sellerService.updateProductForSellerByEmail(userDetails.getUsername(), id, request));
    }

    @Operation(summary = "Delete product owned by seller")
    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> deleteSellerProduct(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        sellerService.deleteProductForSellerByEmail(userDetails.getUsername(), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get seller order items and fulfillment tracking")
    @GetMapping("/orders")
    public ResponseEntity<List<SellerOrderResponse>> getSellerOrders(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(sellerService.getSellerOrdersByEmail(userDetails.getUsername()));
    }
}
