package com.orderflow.repository;

import com.orderflow.model.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    Optional<ProductVariant> findBySku(String sku);

    boolean existsBySku(String sku);

    List<ProductVariant> findByProductId(Long productId);

    Optional<ProductVariant> findByProductIdAndIsDefaultTrue(Long productId);

    Optional<ProductVariant> findByIdAndProductId(Long id, Long productId);

    List<ProductVariant> findByProductIdIn(List<Long> productIds);

    List<ProductVariant> findByIsDefaultTrue();
}
