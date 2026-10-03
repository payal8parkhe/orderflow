package com.orderflow.service;

import com.orderflow.dto.InventoryResponse;
import com.orderflow.dto.InventoryUpdateRequest;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.model.Inventory;
import com.orderflow.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    @Transactional(readOnly = true)
    public InventoryResponse getInventoryByProductId(Long productId) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory not found for product id: " + productId));

        return mapToResponse(inventory);
    }

    @Transactional
    @CacheEvict(value = "products", key = "#productId")
    public InventoryResponse updateStock(Long productId, InventoryUpdateRequest request) {
        Inventory inventory = inventoryRepository.findByProductIdWithLock(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory not found for product id: " + productId));

        inventory.setAvailableStock(request.getAvailableStock());
        Inventory saved = inventoryRepository.save(inventory);

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public InventoryResponse getInventoryByVariantId(Long variantId) {
        Inventory inventory = inventoryRepository.findByVariantId(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory not found for variant id: " + variantId));

        return mapToResponse(inventory);
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public InventoryResponse updateStockByVariantId(Long variantId, InventoryUpdateRequest request) {
        Inventory inventory = inventoryRepository.findByVariantIdWithLock(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory not found for variant id: " + variantId));

        inventory.setAvailableStock(request.getAvailableStock());
        Inventory saved = inventoryRepository.save(inventory);

        return mapToResponse(saved);
    }

    private InventoryResponse mapToResponse(Inventory inventory) {
        InventoryResponse.InventoryResponseBuilder builder = InventoryResponse.builder()
                .id(inventory.getId())
                .availableStock(inventory.getAvailableStock())
                .reservedStock(inventory.getReservedStock())
                .version(inventory.getVersion());

        if (inventory.getProduct() != null) {
            builder.productId(inventory.getProduct().getId())
                    .productSku(inventory.getProduct().getSku())
                    .productName(inventory.getProduct().getName());
        }

        if (inventory.getVariant() != null) {
            builder.variantId(inventory.getVariant().getId())
                    .variantSku(inventory.getVariant().getSku())
                    .variantTitle(inventory.getVariant().getTitle());
        }

        return builder.build();
    }
}
