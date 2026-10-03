package com.orderflow.model;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "products", indexes = {
        @Index(name = "idx_catalog_product_sku", columnList = "sku", unique = true),
        @Index(name = "idx_catalog_product_category", columnList = "category"),
        @Index(name = "idx_catalog_product_seller", columnList = "seller_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    private String category;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "seller_id")
    private Long sellerId;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ProductVariant> variants = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void addVariant(ProductVariant variant) {
        if (this.variants == null) {
            this.variants = new ArrayList<>();
        }
        this.variants.add(variant);
        variant.setProduct(this);
    }

    public Optional<ProductVariant> getDefaultVariant() {
        if (variants == null || variants.isEmpty()) {
            return Optional.empty();
        }
        return variants.stream()
                .filter(ProductVariant::isDefault)
                .findFirst()
                .or(() -> variants.stream().findFirst());
    }
}
