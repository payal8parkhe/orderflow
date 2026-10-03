package com.orderflow.controller;

import com.orderflow.dto.ProductRequest;
import com.orderflow.dto.ProductResponse;
import com.orderflow.dto.ProductVariantRequest;
import com.orderflow.dto.ProductVariantResponse;
import com.orderflow.service.ProductService;
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
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Product catalog browsing, Redis-cached queries, SKU variants, and administrative product management")
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "Get paginated products", description = "Retrieve all catalog products with pagination, sorting, and live stock across variants")
    @GetMapping
    public ResponseEntity<Page<ProductResponse>> getAllProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {

        int boundedSize = Math.min(Math.max(size, 1), 100);
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, boundedSize, sort);
        return ResponseEntity.ok(productService.getAllProducts(pageable));
    }

    @Operation(summary = "Get product by ID", description = "Retrieve product catalog item with all available sellable SKU variants")
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProductById(id));
    }

    @Operation(summary = "Get products by category", description = "Filter products by category with pagination")
    @GetMapping("/category/{category}")
    public ResponseEntity<Page<ProductResponse>> getProductsByCategory(
            @PathVariable String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        int boundedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(page, boundedSize);
        return ResponseEntity.ok(productService.getProductsByCategory(category, pageable));
    }

    @Operation(summary = "Create product", description = "Create a new catalog product (with optional variants) as an administrator")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        ProductResponse response = productService.createProduct(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @Operation(summary = "Update product", description = "Update product catalog details and pricing as an administrator")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Long id,
            @RequestBody ProductRequest request) {
        ProductResponse response = productService.updateProduct(id, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Add variant to product", description = "Create a new SKU-level variant under an existing parent product with initial stock")
    @PostMapping("/{id}/variants")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductVariantResponse> addVariant(
            @PathVariable Long id,
            @Valid @RequestBody ProductVariantRequest request) {
        ProductVariantResponse response = productService.addVariant(id, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @Operation(summary = "List variants for product", description = "Fetch all sellable SKU variants and inventory for a specific parent product")
    @GetMapping("/{id}/variants")
    public ResponseEntity<List<ProductVariantResponse>> getVariants(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getVariantsByProductId(id));
    }

    @Operation(summary = "Get variant by ID", description = "Fetch a specific SKU variant under a product")
    @GetMapping("/{id}/variants/{variantId}")
    public ResponseEntity<ProductVariantResponse> getVariantById(
            @PathVariable Long id,
            @PathVariable Long variantId) {
        return ResponseEntity.ok(productService.getVariantById(id, variantId));
    }
}
