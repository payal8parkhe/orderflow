package com.orderflow.service;

import com.orderflow.dto.ProductRequest;
import com.orderflow.dto.ProductResponse;
import com.orderflow.dto.SellerOrderResponse;
import com.orderflow.dto.SellerProfileRequest;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.model.OrderItem;
import com.orderflow.model.Product;
import com.orderflow.model.Seller;
import com.orderflow.model.User;
import com.orderflow.repository.OrderItemRepository;
import com.orderflow.repository.ProductRepository;
import com.orderflow.repository.SellerRepository;
import com.orderflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SellerService {

    private final SellerRepository sellerRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;
    private final UserRepository userRepository;
    private final OrderItemRepository orderItemRepository;

    @Transactional
    public Seller resolveSellerByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
        return getOrCreateSellerProfile(user.getId());
    }

    @Transactional
    public Seller getOrCreateSellerProfile(Long userId) {
        return sellerRepository.findByUserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
                    Seller seller = Seller.builder()
                            .userId(userId)
                            .businessName(user.getFullName() + "'s Store")
                            .contactEmail(user.getEmail())
                            .status("ACTIVE")
                            .build();
                    return sellerRepository.save(seller);
                });
    }

    @Transactional
    public Seller getSellerProfileByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
        return getOrCreateSellerProfile(user.getId());
    }

    @Transactional
    public Seller updateSellerProfileByEmail(String email, SellerProfileRequest request) {
        Seller seller = resolveSellerByEmail(email);
        return updateSellerProfile(seller.getUserId(), request);
    }

    @Transactional
    public List<ProductResponse> getSellerProductsByEmail(String email) {
        Seller seller = resolveSellerByEmail(email);
        return getSellerProducts(seller.getUserId());
    }

    @Transactional
    public ProductResponse createProductForSellerByEmail(String email, ProductRequest request) {
        Seller seller = resolveSellerByEmail(email);
        return createProductForSeller(seller.getUserId(), request);
    }

    @Transactional
    public ProductResponse updateProductForSellerByEmail(String email, Long productId, ProductRequest request) {
        Seller seller = resolveSellerByEmail(email);
        return updateProductForSeller(seller.getUserId(), productId, request);
    }

    @Transactional
    public void deleteProductForSellerByEmail(String email, Long productId) {
        Seller seller = resolveSellerByEmail(email);
        deleteProductForSeller(seller.getUserId(), productId);
    }

    @Transactional
    public List<SellerOrderResponse> getSellerOrdersByEmail(String email) {
        Seller seller = resolveSellerByEmail(email);
        return getSellerOrders(seller.getUserId());
    }

    @Transactional
    public Seller updateSellerProfile(Long userId, SellerProfileRequest request) {
        Seller seller = getOrCreateSellerProfile(userId);
        seller.setBusinessName(request.getBusinessName());
        if (request.getContactEmail() != null) seller.setContactEmail(request.getContactEmail());
        if (request.getPhoneNumber() != null) seller.setPhoneNumber(request.getPhoneNumber());
        if (request.getTaxId() != null) seller.setTaxId(request.getTaxId());
        if (request.getPayoutDetails() != null) seller.setPayoutDetails(request.getPayoutDetails());
        return sellerRepository.save(seller);
    }

    @Transactional
    public List<ProductResponse> getSellerProducts(Long userId) {
        Seller seller = getOrCreateSellerProfile(userId);
        List<Product> products = productRepository.findBySellerId(seller.getId());
        return products.stream()
                .map(p -> productService.getProductById(p.getId()))
                .collect(Collectors.toList());
    }

    @Transactional
    public ProductResponse createProductForSeller(Long userId, ProductRequest request) {
        Seller seller = getOrCreateSellerProfile(userId);
        ProductResponse created = productService.createProduct(request);
        Product product = productRepository.findById(created.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        product.setSellerId(seller.getId());
        productRepository.save(product);
        created.setSellerId(seller.getId());
        return created;
    }

    @Transactional
    public ProductResponse updateProductForSeller(Long userId, Long productId, ProductRequest request) {
        Seller seller = getOrCreateSellerProfile(userId);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        if (product.getSellerId() != null && !product.getSellerId().equals(seller.getId())) {
            throw new AccessDeniedException("Access denied: You do not own product #" + productId);
        }

        ProductResponse updated = productService.updateProduct(productId, request);
        product.setSellerId(seller.getId());
        productRepository.save(product);
        updated.setSellerId(seller.getId());
        return updated;
    }

    @Transactional
    public void deleteProductForSeller(Long userId, Long productId) {
        Seller seller = getOrCreateSellerProfile(userId);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        if (product.getSellerId() != null && !product.getSellerId().equals(seller.getId())) {
            throw new AccessDeniedException("Access denied: You do not own product #" + productId);
        }

        productService.deleteProduct(productId);
    }

    @Transactional
    public List<SellerOrderResponse> getSellerOrders(Long userId) {
        Seller seller = getOrCreateSellerProfile(userId);
        List<OrderItem> items = orderItemRepository.findByProductSellerId(seller.getId());
        return items.stream().map(item -> SellerOrderResponse.builder()
                .orderId(item.getOrder().getId())
                .orderNumber(item.getOrder().getOrderNumber())
                .customerEmail(item.getOrder().getUser().getEmail())
                .productId(item.getProduct().getId())
                .productName(item.getProduct().getName())
                .sku(item.getSku())
                .variantTitle(item.getVariantTitle())
                .quantity(item.getQuantity())
                .unitPrice(item.getPricePerUnit())
                .subtotal(item.getSubtotal())
                .orderStatus(item.getOrder().getStatus())
                .orderDate(item.getOrder().getCreatedAt())
                .build()
        ).collect(Collectors.toList());
    }
}
