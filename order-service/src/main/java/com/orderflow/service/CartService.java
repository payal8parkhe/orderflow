package com.orderflow.service;

import com.orderflow.dto.*;
import com.orderflow.exception.BadRequestException;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.kafka.CartEventProducer;
import com.orderflow.model.*;
import com.orderflow.repository.CartItemRepository;
import com.orderflow.repository.InventoryRepository;
import com.orderflow.repository.ProductRepository;
import com.orderflow.repository.ProductVariantRepository;
import com.orderflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryRepository inventoryRepository;
    private final UserRepository userRepository;
    private final OrderService orderService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CartEventProducer cartEventProducer;

    private static final String CART_CACHE_PREFIX = "cart:cache:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private String getCacheKey(String userEmail, String guestSessionId) {
        if (userEmail != null && !userEmail.isBlank()) {
            return CART_CACHE_PREFIX + "user:" + userEmail.toLowerCase().trim();
        }
        return CART_CACHE_PREFIX + "guest:" + (guestSessionId != null ? guestSessionId.trim() : "anonymous");
    }

    public void evictCache(String userEmail, String guestSessionId) {
        String key = getCacheKey(userEmail, guestSessionId);
        redisTemplate.delete(key);
    }

    public void evictAllCartCaches() {
        Set<String> keys = redisTemplate.keys(CART_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private User resolveUser(String userEmail) {
        if (userEmail == null || userEmail.isBlank()) return null;
        return userRepository.findByEmail(userEmail).orElse(null);
    }

    @Transactional(readOnly = true)
    public CartResponse getCart(String userEmail, String guestSessionId) {
        String cacheKey = getCacheKey(userEmail, guestSessionId);
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof CartResponse cartResponse) {
            return cartResponse;
        }

        User user = resolveUser(userEmail);
        List<CartItem> allItems;
        if (user != null) {
            allItems = cartItemRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        } else if (guestSessionId != null && !guestSessionId.isBlank()) {
            allItems = cartItemRepository.findByGuestSessionIdOrderByCreatedAtDesc(guestSessionId);
        } else {
            return CartResponse.builder()
                    .items(Collections.emptyList())
                    .totalItems(0)
                    .totalAmount(BigDecimal.ZERO)
                    .savedItems(Collections.emptyList())
                    .savedItemsCount(0)
                    .build();
        }

        List<CartItemResponse> activeResponses = new ArrayList<>();
        List<CartItemResponse> savedResponses = new ArrayList<>();
        int activeTotalItems = 0;
        BigDecimal activeTotalAmount = BigDecimal.ZERO;

        for (CartItem item : allItems) {
            Product product = item.getProduct();
            ProductVariant variant = item.getVariant();

            Inventory inventory = null;
            if (variant != null) {
                inventory = inventoryRepository.findByVariantId(variant.getId()).orElse(null);
            }
            if (inventory == null && product != null) {
                inventory = inventoryRepository.findByProductId(product.getId()).orElse(null);
            }

            int availableStock = inventory != null ? inventory.getAvailableStock() : 0;
            boolean inStock = availableStock >= item.getQuantity();

            // Stock telemetry warning
            String stockWarning = null;
            if (availableStock <= 0) {
                inStock = false;
                stockWarning = "Currently Out of Stock";
            } else if (availableStock < item.getQuantity()) {
                stockWarning = String.format("Only %d available (requested %d)", availableStock, item.getQuantity());
            } else if (availableStock <= 5) {
                stockWarning = String.format("Only %d left in stock - order soon", availableStock);
            }

            // Dynamic pricing telemetry
            BigDecimal currentPrice = variant != null ? variant.getEffectivePrice() : (product != null ? product.getPrice() : BigDecimal.ZERO);
            BigDecimal addedPrice = item.getPriceWhenAdded();
            BigDecimal diff = currentPrice.subtract(addedPrice);
            String priceAlert = null;
            if (diff.compareTo(BigDecimal.ZERO) < 0) {
                priceAlert = String.format("Price dropped by ₹%s since added to cart!", diff.abs().toPlainString());
            } else if (diff.compareTo(BigDecimal.ZERO) > 0) {
                priceAlert = String.format("Price increased by ₹%s", diff.toPlainString());
            }

            BigDecimal subtotal = currentPrice.multiply(BigDecimal.valueOf(item.getQuantity()));

            CartItemResponse response = CartItemResponse.builder()
                    .id(item.getId())
                    .productId(product != null ? product.getId() : null)
                    .productName(product != null ? product.getName() : null)
                    .productSku(product != null ? product.getSku() : null)
                    .variantId(variant != null ? variant.getId() : null)
                    .variantTitle(variant != null ? variant.getTitle() : null)
                    .variantSku(variant != null ? variant.getSku() : null)
                    .variantAttributes(variant != null && variant.getAttributes() != null ? new HashMap<>(variant.getAttributes()) : null)
                    .imageUrl(variant != null ? variant.getEffectiveImageUrl() : (product != null ? product.getImageUrl() : null))
                    .price(currentPrice)
                    .priceWhenAdded(addedPrice)
                    .priceDifference(diff)
                    .priceAlert(priceAlert)
                    .quantity(item.getQuantity())
                    .subtotal(subtotal)
                    .availableStock(availableStock)
                    .inStock(inStock)
                    .stockWarning(stockWarning)
                    .status(item.getStatus())
                    .build();

            if (item.getStatus() == CartItemStatus.ACTIVE) {
                activeResponses.add(response);
                if (inStock) {
                    activeTotalAmount = activeTotalAmount.add(subtotal);
                    activeTotalItems += item.getQuantity();
                }
            } else {
                savedResponses.add(response);
            }
        }

        CartResponse cartResponse = CartResponse.builder()
                .items(activeResponses)
                .totalItems(activeTotalItems)
                .totalAmount(activeTotalAmount)
                .savedItems(savedResponses)
                .savedItemsCount(savedResponses.size())
                .build();

        redisTemplate.opsForValue().set(cacheKey, cartResponse, CACHE_TTL);
        return cartResponse;
    }

    @Transactional
    public CartResponse addItem(String userEmail, String guestSessionId, CartItemRequest request) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + request.getProductId()));

        ProductVariant variant = null;
        if (request.getVariantId() != null) {
            variant = productVariantRepository.findByIdAndProductId(request.getVariantId(), product.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(String.format("Variant %d not found for product %d", request.getVariantId(), product.getId())));
        } else if (productVariantRepository != null) {
            variant = productVariantRepository.findByProductIdAndIsDefaultTrue(product.getId())
                    .or(() -> productVariantRepository.findByProductId(product.getId()).stream().findFirst())
                    .orElse(null);
        }

        User user = resolveUser(userEmail);
        Optional<CartItem> existingItemOpt;
        if (variant != null) {
            if (user != null) {
                existingItemOpt = cartItemRepository.findByUserIdAndProductIdAndVariantIdAndStatus(user.getId(), product.getId(), variant.getId(), CartItemStatus.ACTIVE);
            } else if (guestSessionId != null && !guestSessionId.isBlank()) {
                existingItemOpt = cartItemRepository.findByGuestSessionIdAndProductIdAndVariantIdAndStatus(guestSessionId, product.getId(), variant.getId(), CartItemStatus.ACTIVE);
            } else {
                throw new BadRequestException("Either user authentication or X-Guest-Session-ID is required");
            }
        } else {
            if (user != null) {
                existingItemOpt = cartItemRepository.findByUserIdAndProductIdAndStatus(user.getId(), product.getId(), CartItemStatus.ACTIVE);
            } else if (guestSessionId != null && !guestSessionId.isBlank()) {
                existingItemOpt = cartItemRepository.findByGuestSessionIdAndProductIdAndStatus(guestSessionId, product.getId(), CartItemStatus.ACTIVE);
            } else {
                throw new BadRequestException("Either user authentication or X-Guest-Session-ID is required");
            }
        }

        BigDecimal effectivePrice = variant != null ? variant.getEffectivePrice() : product.getPrice();

        if (existingItemOpt.isPresent()) {
            CartItem existing = existingItemOpt.get();
            existing.setQuantity(existing.getQuantity() + request.getQuantity());
            cartItemRepository.save(existing);
        } else {
            CartItem newItem = CartItem.builder()
                    .user(user)
                    .guestSessionId(user == null ? guestSessionId : null)
                    .product(product)
                    .variant(variant)
                    .quantity(request.getQuantity())
                    .priceWhenAdded(effectivePrice)
                    .status(CartItemStatus.ACTIVE)
                    .build();
            cartItemRepository.save(newItem);
        }

        evictCache(userEmail, guestSessionId);

        // Kafka Event
        cartEventProducer.sendCartEvent(CartEventPayload.builder()
                .eventType("ITEM_ADDED")
                .userId(user != null ? user.getId() : null)
                .userEmail(userEmail)
                .guestSessionId(guestSessionId)
                .productId(product.getId())
                .productSku(variant != null ? variant.getSku() : product.getSku())
                .quantity(request.getQuantity())
                .price(effectivePrice)
                .timestamp(LocalDateTime.now())
                .build());

        return getCart(userEmail, guestSessionId);
    }

    @Transactional
    public CartResponse updateItemQuantity(String userEmail, String guestSessionId, Long identifier, Integer quantity) {
        User user = resolveUser(userEmail);
        Optional<CartItem> itemOpt = Optional.empty();

        if (identifier != null) {
            Optional<CartItem> byId = cartItemRepository.findById(identifier);
            if (byId.isPresent()) {
                CartItem ci = byId.get();
                boolean belongs = (user != null && ci.getUser() != null && ci.getUser().getId().equals(user.getId()))
                        || (user == null && guestSessionId != null && guestSessionId.equals(ci.getGuestSessionId()));
                if (belongs) {
                    itemOpt = byId;
                }
            }
        }

        if (itemOpt.isEmpty()) {
            if (user != null) {
                itemOpt = cartItemRepository.findByUserIdAndProductIdAndStatus(user.getId(), identifier, CartItemStatus.ACTIVE);
            } else {
                itemOpt = cartItemRepository.findByGuestSessionIdAndProductIdAndStatus(guestSessionId, identifier, CartItemStatus.ACTIVE);
            }
        }

        if (itemOpt.isPresent()) {
            CartItem item = itemOpt.get();
            if (quantity == null || quantity <= 0) {
                cartItemRepository.delete(item);
            } else {
                item.setQuantity(quantity);
                cartItemRepository.save(item);
            }
            evictCache(userEmail, guestSessionId);
        }

        return getCart(userEmail, guestSessionId);
    }

    @Transactional
    public CartResponse removeItem(String userEmail, String guestSessionId, Long identifier) {
        User user = resolveUser(userEmail);
        boolean deleted = false;

        if (identifier != null) {
            Optional<CartItem> byId = cartItemRepository.findById(identifier);
            if (byId.isPresent()) {
                CartItem ci = byId.get();
                boolean belongs = (user != null && ci.getUser() != null && ci.getUser().getId().equals(user.getId()))
                        || (user == null && guestSessionId != null && guestSessionId.equals(ci.getGuestSessionId()));
                if (belongs) {
                    cartItemRepository.delete(ci);
                    deleted = true;
                }
            }
        }

        if (!deleted) {
            List<CartItem> items;
            if (user != null) {
                items = cartItemRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
            } else {
                items = cartItemRepository.findByGuestSessionIdOrderByCreatedAtDesc(guestSessionId);
            }

            items.stream()
                    .filter(i -> i.getProduct().getId().equals(identifier))
                    .forEach(cartItemRepository::delete);
        }

        Long eventProductId = identifier;
        if (deleted && identifier != null) {
            Optional<CartItem> byId = cartItemRepository.findById(identifier);
            if (byId.isPresent() && byId.get().getProduct() != null) {
                eventProductId = byId.get().getProduct().getId();
            }
        }

        cartEventProducer.sendCartEvent(CartEventPayload.builder()
                .eventType("ITEM_REMOVED")
                .userId(user != null ? user.getId() : null)
                .userEmail(userEmail)
                .guestSessionId(guestSessionId)
                .productId(eventProductId)
                .timestamp(LocalDateTime.now())
                .build());

        return getCart(userEmail, guestSessionId);
    }

    @Transactional
    public CartResponse saveForLater(String userEmail, String guestSessionId, Long productId) {
        User user = resolveUser(userEmail);
        Optional<CartItem> itemOpt;
        if (user != null) {
            itemOpt = cartItemRepository.findByUserIdAndProductIdAndStatus(user.getId(), productId, CartItemStatus.ACTIVE);
        } else {
            itemOpt = cartItemRepository.findByGuestSessionIdAndProductIdAndStatus(guestSessionId, productId, CartItemStatus.ACTIVE);
        }

        if (itemOpt.isPresent()) {
            CartItem item = itemOpt.get();
            item.setStatus(CartItemStatus.SAVED_FOR_LATER);
            cartItemRepository.save(item);
            evictCache(userEmail, guestSessionId);

            cartEventProducer.sendCartEvent(CartEventPayload.builder()
                    .eventType("SAVED_FOR_LATER")
                    .userId(user != null ? user.getId() : null)
                    .userEmail(userEmail)
                    .guestSessionId(guestSessionId)
                    .productId(productId)
                    .timestamp(LocalDateTime.now())
                    .build());
        }

        return getCart(userEmail, guestSessionId);
    }

    @Transactional
    public CartResponse moveToCart(String userEmail, String guestSessionId, Long productId) {
        User user = resolveUser(userEmail);
        Optional<CartItem> itemOpt;
        if (user != null) {
            itemOpt = cartItemRepository.findByUserIdAndProductIdAndStatus(user.getId(), productId, CartItemStatus.SAVED_FOR_LATER);
        } else {
            itemOpt = cartItemRepository.findByGuestSessionIdAndProductIdAndStatus(guestSessionId, productId, CartItemStatus.SAVED_FOR_LATER);
        }

        if (itemOpt.isPresent()) {
            CartItem item = itemOpt.get();
            item.setStatus(CartItemStatus.ACTIVE);
            BigDecimal currentPrice = item.getVariant() != null ? item.getVariant().getEffectivePrice() : item.getProduct().getPrice();
            item.setPriceWhenAdded(currentPrice); // Refresh price milestone
            cartItemRepository.save(item);
            evictCache(userEmail, guestSessionId);

            cartEventProducer.sendCartEvent(CartEventPayload.builder()
                    .eventType("MOVED_TO_CART")
                    .userId(user != null ? user.getId() : null)
                    .userEmail(userEmail)
                    .guestSessionId(guestSessionId)
                    .productId(productId)
                    .timestamp(LocalDateTime.now())
                    .build());
        }

        return getCart(userEmail, guestSessionId);
    }

    @Transactional
    public void clearCart(String userEmail, String guestSessionId) {
        User user = resolveUser(userEmail);
        if (user != null) {
            cartItemRepository.deleteByUserIdAndStatus(user.getId(), CartItemStatus.ACTIVE);
        } else if (guestSessionId != null) {
            cartItemRepository.deleteByGuestSessionId(guestSessionId);
        }
        evictCache(userEmail, guestSessionId);
    }

    @Transactional
    public CartResponse mergeGuestCart(String userEmail, String guestSessionId) {
        if (guestSessionId == null || guestSessionId.isBlank()) {
            return getCart(userEmail, null);
        }

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        List<CartItem> guestItems = cartItemRepository.findByGuestSessionIdOrderByCreatedAtDesc(guestSessionId);
        for (CartItem guestItem : guestItems) {
            Optional<CartItem> existingUserItem;
            if (guestItem.getVariant() != null) {
                existingUserItem = cartItemRepository.findByUserIdAndProductIdAndVariantIdAndStatus(
                        user.getId(), guestItem.getProduct().getId(), guestItem.getVariant().getId(), guestItem.getStatus());
            } else {
                existingUserItem = cartItemRepository.findByUserIdAndProductIdAndStatus(
                        user.getId(), guestItem.getProduct().getId(), guestItem.getStatus());
            }

            if (existingUserItem.isPresent()) {
                CartItem userItem = existingUserItem.get();
                userItem.setQuantity(userItem.getQuantity() + guestItem.getQuantity());
                cartItemRepository.save(userItem);
                cartItemRepository.delete(guestItem);
            } else {
                guestItem.setUser(user);
                guestItem.setGuestSessionId(null);
                cartItemRepository.save(guestItem);
            }
        }

        evictCache(null, guestSessionId);
        evictCache(userEmail, null);

        log.info("Merged {} guest cart item(s) from session '{}' into user '{}'", guestItems.size(), guestSessionId, userEmail);

        cartEventProducer.sendCartEvent(CartEventPayload.builder()
                .eventType("CART_MERGED")
                .userId(user.getId())
                .userEmail(userEmail)
                .guestSessionId(guestSessionId)
                .timestamp(LocalDateTime.now())
                .build());

        return getCart(userEmail, null);
    }

    @Transactional
    public OrderResponse checkout(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        List<CartItem> activeItems = cartItemRepository.findByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), CartItemStatus.ACTIVE);
        if (activeItems.isEmpty()) {
            throw new BadRequestException("Cannot checkout: No active items in shopping cart");
        }

        // Filter and isolate in-stock items
        List<OrderItemRequest> orderItems = new ArrayList<>();
        List<CartItem> purchasedItems = new ArrayList<>();

        for (CartItem item : activeItems) {
            Inventory inv = null;
            if (item.getVariant() != null) {
                inv = inventoryRepository.findByVariantId(item.getVariant().getId()).orElse(null);
            }
            if (inv == null && item.getProduct() != null) {
                inv = inventoryRepository.findByProductId(item.getProduct().getId()).orElse(null);
            }

            if (inv != null && inv.getAvailableStock() >= item.getQuantity()) {
                orderItems.add(OrderItemRequest.builder()
                        .productId(item.getProduct().getId())
                        .variantId(item.getVariant() != null ? item.getVariant().getId() : null)
                        .quantity(item.getQuantity())
                        .build());
                purchasedItems.add(item);
            } else {
                String name = item.getVariant() != null ? item.getVariant().getTitle() : item.getProduct().getName();
                log.warn("Cart item '{}' skipped during checkout due to stock exhaustion", name);
            }
        }

        if (orderItems.isEmpty()) {
            throw new BadRequestException("Checkout aborted: All active items in your cart are currently out of stock");
        }

        OrderRequest orderRequest = new OrderRequest();
        orderRequest.setItems(orderItems);

        // Transactional checkout with pessimistic write locking on stock
        OrderResponse orderResponse = orderService.createOrder(userEmail, orderRequest);

        // Delete only the successfully checked-out active items from DB
        cartItemRepository.deleteAll(purchasedItems);
        evictCache(userEmail, null);

        log.info("Amazon-grade checkout complete for user {}. Purchased {} item(s). Order #{}",
                userEmail, purchasedItems.size(), orderResponse.getOrderNumber());

        cartEventProducer.sendCartEvent(CartEventPayload.builder()
                .eventType("CHECKOUT_COMPLETED")
                .userId(user.getId())
                .userEmail(userEmail)
                .quantity(purchasedItems.size())
                .price(orderResponse.getTotalAmount())
                .timestamp(LocalDateTime.now())
                .build());

        return orderResponse;
    }
}
