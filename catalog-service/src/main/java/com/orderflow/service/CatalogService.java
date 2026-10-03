package com.orderflow.service;

import com.orderflow.dto.ProductEventPayload;
import com.orderflow.dto.ProductRequest;
import com.orderflow.dto.ProductResponse;
import com.orderflow.dto.ProductVariantRequest;
import com.orderflow.dto.ProductVariantResponse;
import com.orderflow.exception.BadRequestException;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.model.Product;
import com.orderflow.model.ProductVariant;
import com.orderflow.repository.ProductRepository;
import com.orderflow.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public static final String CATALOG_EVENTS_TOPIC = "catalog-events";

    @Transactional(readOnly = true)
    public Page<ProductResponse> getAllProducts(Pageable pageable) {
        log.debug("Fetching products from database (page: {}, size: {})", pageable.getPageNumber(), pageable.getPageSize());
        Page<Product> productPage = productRepository.findAll(pageable);
        return productPage.map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "products", key = "'id_' + #id")
    public ProductResponse getProductById(Long id) {
        log.debug("Cache miss - fetching product id: {}", id);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        return mapToResponse(product);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "products", key = "'sku_' + #sku")
    public ProductResponse getProductBySku(String sku) {
        log.debug("Cache miss - fetching product sku: {}", sku);
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
        return mapToResponse(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> getProductsByCategory(String category, Pageable pageable) {
        log.debug("Fetching products for category: {}", category);
        return productRepository.findByCategory(category, pageable).map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "products", key = "'categories'")
    public List<String> getCategories() {
        return productRepository.findDistinctCategories();
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public ProductResponse createProduct(ProductRequest request) {
        if (productRepository.existsBySku(request.getSku())) {
            throw new BadRequestException("Product with SKU already exists: " + request.getSku());
        }

        Product product = Product.builder()
                .sku(request.getSku())
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .category(request.getCategory())
                .imageUrl(request.getImageUrl())
                .sellerId(request.getSellerId())
                .build();

        Product savedProduct = productRepository.save(product);

        if (request.getVariants() != null && !request.getVariants().isEmpty()) {
            boolean hasDefault = request.getVariants().stream().anyMatch(v -> Boolean.TRUE.equals(v.getIsDefault()));
            boolean first = true;
            for (ProductVariantRequest vr : request.getVariants()) {
                if (productVariantRepository.existsBySku(vr.getSku())) {
                    throw new BadRequestException("Variant SKU already exists: " + vr.getSku());
                }
                boolean isDefault = Boolean.TRUE.equals(vr.getIsDefault()) || (!hasDefault && first);
                ProductVariant variant = ProductVariant.builder()
                        .product(savedProduct)
                        .sku(vr.getSku())
                        .title(vr.getTitle())
                        .price(vr.getPrice() != null ? vr.getPrice() : savedProduct.getPrice())
                        .isDefault(isDefault)
                        .imageUrl(vr.getImageUrl() != null ? vr.getImageUrl() : savedProduct.getImageUrl())
                        .active(true)
                        .attributes(vr.getAttributes() != null ? vr.getAttributes() : new HashMap<>())
                        .build();

                savedProduct.addVariant(variant);
                first = false;
            }
            savedProduct = productRepository.save(savedProduct);
        }

        publishCatalogEvent("PRODUCT_CREATED", savedProduct.getId(), null, savedProduct.getSku(), savedProduct.getName(),
                savedProduct.getCategory(), savedProduct.getPrice(), savedProduct.getSellerId(), request.getInitialStock());

        log.info("Catalog Product created: [ID: {}, SKU: {}, Name: {}]", savedProduct.getId(), savedProduct.getSku(), savedProduct.getName());
        return mapToResponse(savedProduct);
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public ProductResponse updateProduct(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        if (!product.getSku().equals(request.getSku()) && productRepository.existsBySku(request.getSku())) {
            throw new BadRequestException("Product with SKU already exists: " + request.getSku());
        }

        product.setSku(request.getSku());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setCategory(request.getCategory());
        product.setImageUrl(request.getImageUrl());
        if (request.getSellerId() != null) {
            product.setSellerId(request.getSellerId());
        }

        Product updated = productRepository.save(product);

        publishCatalogEvent("PRODUCT_UPDATED", updated.getId(), null, updated.getSku(), updated.getName(),
                updated.getCategory(), updated.getPrice(), updated.getSellerId(), null);

        log.info("Catalog Product updated: [ID: {}, SKU: {}]", updated.getId(), updated.getSku());
        return mapToResponse(updated);
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        productRepository.delete(product);

        publishCatalogEvent("PRODUCT_DELETED", id, null, product.getSku(), product.getName(),
                product.getCategory(), product.getPrice(), product.getSellerId(), null);

        log.info("Catalog Product deleted: [ID: {}, SKU: {}]", id, product.getSku());
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public ProductVariantResponse addVariant(Long productId, ProductVariantRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        if (productVariantRepository.existsBySku(request.getSku())) {
            throw new BadRequestException("Variant SKU already exists: " + request.getSku());
        }

        boolean isDefault = Boolean.TRUE.equals(request.getIsDefault());
        if (isDefault) {
            List<ProductVariant> existing = productVariantRepository.findByProductId(productId);
            for (ProductVariant v : existing) {
                if (v.isDefault()) {
                    v.setDefault(false);
                    productVariantRepository.save(v);
                }
            }
        }

        ProductVariant variant = ProductVariant.builder()
                .product(product)
                .sku(request.getSku())
                .title(request.getTitle())
                .price(request.getPrice() != null ? request.getPrice() : product.getPrice())
                .isDefault(isDefault)
                .imageUrl(request.getImageUrl() != null ? request.getImageUrl() : product.getImageUrl())
                .active(true)
                .attributes(request.getAttributes() != null ? request.getAttributes() : new HashMap<>())
                .build();

        ProductVariant saved = productVariantRepository.save(variant);

        publishCatalogEvent("VARIANT_CREATED", product.getId(), saved.getId(), saved.getSku(), saved.getTitle(),
                product.getCategory(), saved.getEffectivePrice(), product.getSellerId(), request.getInitialStock());

        log.info("Variant added to Product {}: [Variant ID: {}, SKU: {}]", productId, saved.getId(), saved.getSku());
        return mapVariantToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ProductVariantResponse> getVariantsByProductId(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product not found with id: " + productId);
        }
        return productVariantRepository.findByProductId(productId).stream()
                .map(this::mapVariantToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ProductVariantResponse getVariantById(Long productId, Long variantId) {
        ProductVariant variant = productVariantRepository.findByIdAndProductId(variantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException(String.format("Variant %d not found for product %d", variantId, productId)));
        return mapVariantToResponse(variant);
    }

    private void publishCatalogEvent(String eventType, Long productId, Long variantId, String sku, String name,
                                     String category, BigDecimal price, Long sellerId, Integer initialStock) {
        try {
            ProductEventPayload event = ProductEventPayload.builder()
                    .eventType(eventType)
                    .productId(productId)
                    .variantId(variantId)
                    .sku(sku)
                    .name(name)
                    .category(category)
                    .price(price)
                    .sellerId(sellerId)
                    .initialStock(initialStock)
                    .timestamp(LocalDateTime.now())
                    .build();

            kafkaTemplate.send(CATALOG_EVENTS_TOPIC, sku, event);
        } catch (Exception ex) {
            log.warn("Could not publish catalog event to Kafka (continuing): {}", ex.getMessage());
        }
    }

    private ProductResponse mapToResponse(Product p) {
        List<ProductVariantResponse> variants = p.getVariants() != null
                ? p.getVariants().stream().map(this::mapVariantToResponse).collect(Collectors.toList())
                : Collections.emptyList();

        return ProductResponse.builder()
                .id(p.getId())
                .sku(p.getSku())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice())
                .category(p.getCategory())
                .imageUrl(p.getImageUrl())
                .sellerId(p.getSellerId())
                .variants(variants)
                .build();
    }

    private ProductVariantResponse mapVariantToResponse(ProductVariant v) {
        return ProductVariantResponse.builder()
                .id(v.getId())
                .productId(v.getProduct() != null ? v.getProduct().getId() : null)
                .sku(v.getSku())
                .title(v.getTitle())
                .price(v.getEffectivePrice())
                .isDefault(v.isDefault())
                .active(v.isActive())
                .imageUrl(v.getEffectiveImageUrl())
                .attributes(v.getAttributes() != null ? new HashMap<>(v.getAttributes()) : Collections.emptyMap())
                .build();
    }
}
