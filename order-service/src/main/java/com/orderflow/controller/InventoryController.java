package com.orderflow.controller;

import com.orderflow.dto.InventoryResponse;
import com.orderflow.dto.InventoryUpdateRequest;
import com.orderflow.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Tag(name = "Inventory", description = "Stock level inquiries and administrative inventory updates for products and SKU variants")
public class InventoryController {

    private final InventoryService inventoryService;

    @Operation(summary = "Get product inventory", description = "Fetch default variant stock or aggregate stock for a product")
    @GetMapping("/{productId}")
    public ResponseEntity<InventoryResponse> getInventory(@PathVariable Long productId) {
        return ResponseEntity.ok(inventoryService.getInventoryByProductId(productId));
    }

    @Operation(summary = "Update product inventory", description = "Update stock for product's default variant")
    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InventoryResponse> updateInventory(
            @PathVariable Long productId,
            @Valid @RequestBody InventoryUpdateRequest request) {
        return ResponseEntity.ok(inventoryService.updateStock(productId, request));
    }

    @Operation(summary = "Get variant inventory", description = "Fetch available and reserved stock for a specific SKU variant")
    @GetMapping("/variant/{variantId}")
    public ResponseEntity<InventoryResponse> getInventoryByVariant(@PathVariable Long variantId) {
        return ResponseEntity.ok(inventoryService.getInventoryByVariantId(variantId));
    }

    @Operation(summary = "Update variant inventory", description = "Update stock for a specific SKU variant")
    @PutMapping("/variant/{variantId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InventoryResponse> updateInventoryByVariant(
            @PathVariable Long variantId,
            @Valid @RequestBody InventoryUpdateRequest request) {
        return ResponseEntity.ok(inventoryService.updateStockByVariantId(variantId, request));
    }
}
