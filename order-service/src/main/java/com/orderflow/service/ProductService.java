package com.orderflow.service;

import com.orderflow.dto.ProductRequest;
import com.orderflow.dto.ProductResponse;
import com.orderflow.dto.ProductVariantRequest;
import com.orderflow.dto.ProductVariantResponse;
import com.orderflow.exception.BadRequestException;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.model.Inventory;
import com.orderflow.model.Product;
import com.orderflow.model.ProductVariant;
import com.orderflow.repository.InventoryRepository;
import com.orderflow.repository.ProductRepository;
import com.orderflow.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryRepository inventoryRepository;

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
                .build();

        Product savedProduct = productRepository.save(product);

        List<ProductVariantResponse> variantResponses = new ArrayList<>();
        int totalStock = 0;

        if (request.getVariants() != null && !request.getVariants().isEmpty()) {
            boolean hasDefault = request.getVariants().stream().anyMatch(v -> Boolean.TRUE.equals(v.getIsDefault()));
            boolean first = true;

            for (ProductVariantRequest vReq : request.getVariants()) {
                if (productVariantRepository.existsBySku(vReq.getSku())) {
                    throw new BadRequestException("Product variant with SKU already exists: " + vReq.getSku());
                }

                boolean isDefault = Boolean.TRUE.equals(vReq.getIsDefault()) || (!hasDefault && first);
                first = false;

                ProductVariant variant = ProductVariant.builder()
                        .product(savedProduct)
                        .sku(vReq.getSku())
                        .title(vReq.getTitle())
                        .price(vReq.getPrice() != null ? vReq.getPrice() : savedProduct.getPrice())
                        .imageUrl(vReq.getImageUrl() != null ? vReq.getImageUrl() : savedProduct.getImageUrl())
                        .isDefault(isDefault)
                        .active(true)
                        .attributes(vReq.getAttributes() != null ? new HashMap<>(vReq.getAttributes()) : new HashMap<>())
                        .build();

                ProductVariant savedVariant = productVariantRepository.save(variant);

                int stock = vReq.getInitialStock() != null ? vReq.getInitialStock() : 0;
                Inventory inventory = Inventory.builder()
                        .product(savedProduct)
                        .variant(savedVariant)
                        .availableStock(stock)
                        .reservedStock(0)
                        .build();
                inventoryRepository.save(inventory);

                totalStock += stock;
                variantResponses.add(mapToVariantResponse(savedVariant, stock, 0));
            }
        } else {
            // Default single-SKU variant for backwards compatibility
            ProductVariant defaultVariant = ProductVariant.builder()
                    .product(savedProduct)
                    .sku(savedProduct.getSku())
                    .title("Standard")
                    .price(savedProduct.getPrice())
                    .imageUrl(savedProduct.getImageUrl())
                    .isDefault(true)
                    .active(true)
                    .attributes(new HashMap<>())
                    .build();

            ProductVariant savedVariant = productVariantRepository.save(defaultVariant);

            int stock = request.getInitialStock() != null ? request.getInitialStock() : 0;
            Inventory inventory = Inventory.builder()
                    .product(savedProduct)
                    .variant(savedVariant)
                    .availableStock(stock)
                    .reservedStock(0)
                    .build();
            inventoryRepository.save(inventory);

            totalStock = stock;
            variantResponses.add(mapToVariantResponse(savedVariant, stock, 0));
        }

        return mapToProductResponse(savedProduct, totalStock, variantResponses);
    }

    @Cacheable(value = "products", key = "#id")
    @Transactional(readOnly = true)
    public ProductResponse getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        List<ProductVariant> variants = productVariantRepository.findByProductId(id);
        List<Inventory> inventories = inventoryRepository.findAllByProductId(id);
        Map<Long, Inventory> inventoryByVariantMap = inventories.stream()
                .filter(i -> i.getVariant() != null)
                .collect(Collectors.toMap(i -> i.getVariant().getId(), i -> i, (a, b) -> a));

        List<ProductVariantResponse> variantResponses = variants.stream()
                .map(v -> {
                    Inventory inv = inventoryByVariantMap.get(v.getId());
                    int avail = inv != null ? inv.getAvailableStock() : 0;
                    int res = inv != null ? inv.getReservedStock() : 0;
                    return mapToVariantResponse(v, avail, res);
                })
                .collect(Collectors.toList());

        int totalAvailableStock = inventories.stream()
                .mapToInt(Inventory::getAvailableStock)
                .sum();

        return mapToProductResponse(product, totalAvailableStock, variantResponses);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> getAllProducts(Pageable pageable) {
        Page<Product> page = productRepository.findAll(pageable);
        return enrichProductPage(page);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> getProductsByCategory(String category, Pageable pageable) {
        Page<Product> page = productRepository.findByCategory(category, pageable);
        return enrichProductPage(page);
    }

    private Page<ProductResponse> enrichProductPage(Page<Product> page) {
        List<Long> productIds = page.getContent().stream().map(Product::getId).toList();
        if (productIds.isEmpty()) {
            return page.map(p -> mapToProductResponse(p, 0, Collections.emptyList()));
        }

        List<ProductVariant> allVariants = productVariantRepository.findByProductIdIn(productIds);
        List<Inventory> allInventories = inventoryRepository.findByProductIdIn(productIds);

        Map<Long, List<ProductVariant>> variantsByProduct = allVariants.stream()
                .collect(Collectors.groupingBy(v -> v.getProduct().getId()));

        Map<Long, Inventory> inventoryByVariant = allInventories.stream()
                .filter(i -> i.getVariant() != null)
                .collect(Collectors.toMap(i -> i.getVariant().getId(), i -> i, (a, b) -> a));

        Map<Long, Integer> stockByProduct = allInventories.stream()
                .collect(Collectors.groupingBy(
                        i -> i.getProduct() != null ? i.getProduct().getId() : 0L,
                        Collectors.summingInt(Inventory::getAvailableStock)
                ));

        return page.map(product -> {
            List<ProductVariant> variants = variantsByProduct.getOrDefault(product.getId(), Collections.emptyList());
            List<ProductVariantResponse> variantResponses = variants.stream()
                    .map(v -> {
                        Inventory inv = inventoryByVariant.get(v.getId());
                        int avail = inv != null ? inv.getAvailableStock() : 0;
                        int res = inv != null ? inv.getReservedStock() : 0;
                        return mapToVariantResponse(v, avail, res);
                    })
                    .collect(Collectors.toList());

            int totalStock = stockByProduct.getOrDefault(product.getId(), 0);
            return mapToProductResponse(product, totalStock, variantResponses);
        });
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public ProductResponse updateProduct(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        if (request.getName() != null && !request.getName().isBlank()) product.setName(request.getName());
        if (request.getDescription() != null) product.setDescription(request.getDescription());
        if (request.getPrice() != null) product.setPrice(request.getPrice());
        if (request.getCategory() != null && !request.getCategory().isBlank()) product.setCategory(request.getCategory());
        if (request.getImageUrl() != null && !request.getImageUrl().isBlank()) product.setImageUrl(request.getImageUrl());

        Product savedProduct = productRepository.save(product);

        // If stock is specified for a single-SKU product update, update its default variant inventory
        if (request.getInitialStock() != null) {
            Optional<ProductVariant> defaultVariantOpt = productVariantRepository.findByProductIdAndIsDefaultTrue(id);
            if (defaultVariantOpt.isPresent()) {
                ProductVariant defaultVariant = defaultVariantOpt.get();
                Inventory inv = inventoryRepository.findByVariantId(defaultVariant.getId())
                        .orElse(Inventory.builder().product(savedProduct).variant(defaultVariant).reservedStock(0).build());
                inv.setAvailableStock(request.getInitialStock());
                inventoryRepository.save(inv);
            }
        }

        return getProductById(savedProduct.getId());
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public ProductVariantResponse addVariant(Long productId, ProductVariantRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        if (productVariantRepository.existsBySku(request.getSku())) {
            throw new BadRequestException("Product variant with SKU already exists: " + request.getSku());
        }

        boolean isDefault = Boolean.TRUE.equals(request.getIsDefault());
        if (isDefault) {
            // Unset previous default variant
            productVariantRepository.findByProductIdAndIsDefaultTrue(productId).ifPresent(v -> {
                v.setDefault(false);
                productVariantRepository.save(v);
            });
        }

        ProductVariant variant = ProductVariant.builder()
                .product(product)
                .sku(request.getSku())
                .title(request.getTitle())
                .price(request.getPrice() != null ? request.getPrice() : product.getPrice())
                .imageUrl(request.getImageUrl() != null ? request.getImageUrl() : product.getImageUrl())
                .isDefault(isDefault)
                .active(true)
                .attributes(request.getAttributes() != null ? new HashMap<>(request.getAttributes()) : new HashMap<>())
                .build();

        ProductVariant savedVariant = productVariantRepository.save(variant);

        int initialStock = request.getInitialStock() != null ? request.getInitialStock() : 0;
        Inventory inventory = Inventory.builder()
                .product(product)
                .variant(savedVariant)
                .availableStock(initialStock)
                .reservedStock(0)
                .build();
        inventoryRepository.save(inventory);

        log.info("Created new variant SKU '{}' ({}) for product '{}'", savedVariant.getSku(), savedVariant.getTitle(), product.getName());
        return mapToVariantResponse(savedVariant, initialStock, 0);
    }

    @Transactional(readOnly = true)
    public List<ProductVariantResponse> getVariantsByProductId(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product not found with id: " + productId);
        }

        List<ProductVariant> variants = productVariantRepository.findByProductId(productId);
        List<Inventory> inventories = inventoryRepository.findAllByProductId(productId);
        Map<Long, Inventory> inventoryByVariantMap = inventories.stream()
                .filter(i -> i.getVariant() != null)
                .collect(Collectors.toMap(i -> i.getVariant().getId(), i -> i, (a, b) -> a));

        return variants.stream()
                .map(v -> {
                    Inventory inv = inventoryByVariantMap.get(v.getId());
                    int avail = inv != null ? inv.getAvailableStock() : 0;
                    int res = inv != null ? inv.getReservedStock() : 0;
                    return mapToVariantResponse(v, avail, res);
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ProductVariantResponse getVariantById(Long productId, Long variantId) {
        ProductVariant variant = productVariantRepository.findByIdAndProductId(variantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException(String.format("Variant %d not found for product %d", variantId, productId)));

        Inventory inv = inventoryRepository.findByVariantId(variantId).orElse(null);
        int avail = inv != null ? inv.getAvailableStock() : 0;
        int res = inv != null ? inv.getReservedStock() : 0;
        return mapToVariantResponse(variant, avail, res);
    }

    @Transactional
    @org.springframework.cache.annotation.Caching(evict = {
            @CacheEvict(value = "products", key = "#id"),
            @CacheEvict(value = "products", allEntries = true)
    })
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        if (product.getVariants() != null) {
            for (ProductVariant variant : product.getVariants()) {
                inventoryRepository.findByVariantId(variant.getId()).ifPresent(inventoryRepository::delete);
            }
        }

        productRepository.delete(product);
        log.info("Deleted product #{} and its associated SKU inventories", id);
    }

    private ProductResponse mapToProductResponse(Product product, Integer totalStock, List<ProductVariantResponse> variants) {
        return ProductResponse.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .category(product.getCategory())
                .imageUrl(product.getImageUrl())
                .sellerId(product.getSellerId())
                .availableStock(totalStock)
                .variants(variants)
                .build();
    }

    private ProductVariantResponse mapToVariantResponse(ProductVariant variant, int availableStock, int reservedStock) {
        return ProductVariantResponse.builder()
                .id(variant.getId())
                .productId(variant.getProduct().getId())
                .sku(variant.getSku())
                .title(variant.getTitle())
                .price(variant.getEffectivePrice())
                .imageUrl(variant.getEffectiveImageUrl())
                .isDefault(variant.isDefault())
                .active(variant.isActive())
                .availableStock(availableStock)
                .reservedStock(reservedStock)
                .attributes(variant.getAttributes() != null ? new HashMap<>(variant.getAttributes()) : Collections.emptyMap())
                .build();
    }
}
