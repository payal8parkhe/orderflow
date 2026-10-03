package com.orderflow.controller;

import com.orderflow.dto.ProductRequest;
import com.orderflow.dto.ProductResponse;
import com.orderflow.dto.ProductVariantRequest;
import com.orderflow.dto.ProductVariantResponse;
import com.orderflow.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/api/products", "/api/catalog"})
@RequiredArgsConstructor
@Tag(name = "Product Catalog", description = "High-performance, Redis-cached product catalog browsing and SKU management microservice")
public class CatalogController {

    private final CatalogService catalogService;

    @Operation(summary = "Get paginated products", description = "Retrieve all catalog products with pagination, sorting, and SKU variants from cache or DB")
    @GetMapping
    public ResponseEntity<Page<ProductResponse>> getAllProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {

        int boundedSize = Math.min(Math.max(size, 1), 100);
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, boundedSize, sort);
        return ResponseEntity.ok(catalogService.getAllProducts(pageable));
    }

    @Operation(summary = "Get product by ID", description = "Retrieve single catalog product with all SKU variants")
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable Long id) {
        return ResponseEntity.ok(catalogService.getProductById(id));
    }

    @Operation(summary = "Get product by SKU", description = "Retrieve single product item by unique SKU identifier")
    @GetMapping("/sku/{sku}")
    public ResponseEntity<ProductResponse> getProductBySku(@PathVariable String sku) {
        return ResponseEntity.ok(catalogService.getProductBySku(sku));
    }

    @Operation(summary = "Get products by category", description = "Filter products by category name with pagination")
    @GetMapping("/category/{category}")
    public ResponseEntity<Page<ProductResponse>> getProductsByCategory(
            @PathVariable String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        int boundedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(page, boundedSize);
        return ResponseEntity.ok(catalogService.getProductsByCategory(category, pageable));
    }

    @Operation(summary = "Get all product categories", description = "List all distinct product categories")
    @GetMapping("/categories")
    public ResponseEntity<List<String>> getCategories() {
        return ResponseEntity.ok(catalogService.getCategories());
    }

    @Operation(summary = "Create catalog product", description = "Create a new catalog product (with optional SKU variants)")
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        ProductResponse response = catalogService.createProduct(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @Operation(summary = "Update product", description = "Update catalog product details and pricing")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Long id,
            @RequestBody ProductRequest request) {
        ProductResponse response = catalogService.updateProduct(id, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Delete product", description = "Remove a product from the catalog")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        catalogService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Add variant to product", description = "Create a new SKU-level variant under an existing parent product")
    @PostMapping("/{id}/variants")
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
    public ResponseEntity<ProductVariantResponse> addVariant(
            @PathVariable Long id,
            @Valid @RequestBody ProductVariantRequest request) {
        ProductVariantResponse response = catalogService.addVariant(id, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @Operation(summary = "List variants for product", description = "Fetch all sellable SKU variants for a specific parent product")
    @GetMapping("/{id}/variants")
    public ResponseEntity<List<ProductVariantResponse>> getVariants(@PathVariable Long id) {
        return ResponseEntity.ok(catalogService.getVariantsByProductId(id));
    }

    @Operation(summary = "Get variant by ID", description = "Fetch a specific SKU variant under a product")
    @GetMapping("/{id}/variants/{variantId}")
    public ResponseEntity<ProductVariantResponse> getVariantById(
            @PathVariable Long id,
            @PathVariable Long variantId) {
        return ResponseEntity.ok(catalogService.getVariantById(id, variantId));
    }
}
