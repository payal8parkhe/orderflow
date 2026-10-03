package com.orderflow.service;

import com.orderflow.dto.*;
import com.orderflow.exception.BadRequestException;
import com.orderflow.exception.InsufficientStockException;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.kafka.OrderEventProducer;
import com.orderflow.model.*;
import com.orderflow.config.KafkaConfig;
import com.orderflow.outbox.OutboxService;
import com.orderflow.repository.InventoryRepository;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.ProductRepository;
import com.orderflow.repository.ProductVariantRepository;
import com.orderflow.repository.UserRepository;
import org.springframework.scheduling.annotation.Scheduled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryRepository inventoryRepository;
    private final UserRepository userRepository;
    private final OrderEventProducer orderEventProducer;
    private final OutboxService outboxService;
    private final AddressService addressService;

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public OrderResponse createOrder(String userEmail, OrderRequest request) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        // Snapshot delivery destination to prevent historical drift
        OrderAddress shippingAddress = addressService.resolveOrderAddress(user, request.getAddressId(), request.getShippingAddress());
        if (shippingAddress == null) {
            shippingAddress = OrderAddress.builder()
                    .recipientName(user.getFullName())
                    .phoneNumber("+1-555-0199")
                    .streetAddress("100 Innovation Way")
                    .city("Tech District")
                    .state("CA")
                    .postalCode("94016")
                    .country("USA")
                    .build();
        }

        Order order = Order.builder()
                .orderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .user(user)
                .status(OrderStatus.AWAITING_PAYMENT)
                .shippingAddress(shippingAddress)
                .estimatedDeliveryDate(LocalDateTime.now().plusDays(3))
                .totalAmount(BigDecimal.ZERO)
                .items(new ArrayList<>())
                .build();

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemRequest.getProductId()));

            ProductVariant variant = null;
            if (itemRequest.getVariantId() != null) {
                variant = productVariantRepository.findByIdAndProductId(itemRequest.getVariantId(), product.getId())
                        .orElseThrow(() -> new ResourceNotFoundException(String.format("Variant %d not found for product %d", itemRequest.getVariantId(), product.getId())));
            } else if (productVariantRepository != null) {
                variant = productVariantRepository.findByProductIdAndIsDefaultTrue(product.getId())
                        .or(() -> productVariantRepository.findByProductId(product.getId()).stream().findFirst())
                        .orElse(null);
            }

            // Pessimistic lock on variant or product inventory
            Inventory inventory = null;
            if (variant != null) {
                inventory = inventoryRepository.findByVariantIdWithLock(variant.getId()).orElse(null);
            }
            if (inventory == null) {
                inventory = inventoryRepository.findByProductIdWithLock(product.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Inventory not found for product: " + product.getSku()));
            }

            if (inventory.getAvailableStock() < itemRequest.getQuantity()) {
                String itemName = variant != null ? (product.getName() + " (" + variant.getTitle() + ")") : product.getName();
                throw new InsufficientStockException(String.format("Insufficient stock for product '%s'. Requested: %d, Available: %d",
                        itemName, itemRequest.getQuantity(), inventory.getAvailableStock()));
            }

            // Reserve/Deduct inventory
            inventory.setAvailableStock(inventory.getAvailableStock() - itemRequest.getQuantity());
            inventory.setReservedStock(inventory.getReservedStock() + itemRequest.getQuantity());
            inventoryRepository.save(inventory);

            BigDecimal pricePerUnit = variant != null ? variant.getEffectivePrice() : product.getPrice();
            BigDecimal itemSubtotal = pricePerUnit.multiply(BigDecimal.valueOf(itemRequest.getQuantity()));
            totalAmount = totalAmount.add(itemSubtotal);

            OrderItem orderItem = OrderItem.builder()
                    .product(product)
                    .variant(variant)
                    .sku(variant != null ? variant.getSku() : product.getSku())
                    .variantTitle(variant != null ? variant.getTitle() : null)
                    .quantity(itemRequest.getQuantity())
                    .pricePerUnit(pricePerUnit)
                    .subtotal(itemSubtotal)
                    .build();

            order.addItem(orderItem);
        }

        order.setTotalAmount(totalAmount);
        Order savedOrder = orderRepository.save(order);


        // Persist Order Created Event to Transactional Outbox (committed in same DB transaction)
        OrderEventPayload eventPayload = OrderEventPayload.builder()
                .eventType("ORDER_CREATED")
                .orderId(savedOrder.getId())
                .orderNumber(savedOrder.getOrderNumber())
                .userId(user.getId())
                .userEmail(user.getEmail())
                .status(savedOrder.getStatus())
                .totalAmount(savedOrder.getTotalAmount())
                .shippingAddress(savedOrder.getShippingAddress())
                .timestamp(LocalDateTime.now())
                .build();

        outboxService.saveEvent("ORDER", savedOrder.getOrderNumber(), "ORDER_CREATED", KafkaConfig.ORDER_EVENTS_TOPIC, eventPayload);

        return mapToOrderResponse(savedOrder);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long orderId, String userEmail) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!user.getRole().equals(Role.ROLE_ADMIN) && !order.getUser().getId().equals(user.getId())) {
            throw new BadRequestException("Unauthorized to view this order");
        }

        return mapToOrderResponse(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getUserOrders(String userEmail, Pageable pageable) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return orderRepository.findByUserId(user.getId(), pageable).map(this::mapToOrderResponse);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getAllOrders(Pageable pageable) {
        return orderRepository.findAll(pageable).map(this::mapToOrderResponse);
    }

    private static final java.util.Map<OrderStatus, java.util.Set<OrderStatus>> ALLOWED_TRANSITIONS = java.util.Map.of(
        OrderStatus.AWAITING_PAYMENT, java.util.Set.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
        OrderStatus.PENDING,          java.util.Set.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
        OrderStatus.CONFIRMED,        java.util.Set.of(OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.CANCELLED),
        OrderStatus.PROCESSING,       java.util.Set.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED),
        OrderStatus.SHIPPED,          java.util.Set.of(OrderStatus.COMPLETED),
        OrderStatus.COMPLETED,        java.util.Set.of(),
        OrderStatus.CANCELLED,        java.util.Set.of()
    );

    private void validateTransition(OrderStatus from, OrderStatus to) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(from, java.util.Set.of()).contains(to)) {
            throw new BadRequestException("Cannot transition order from " + from + " to " + to);
        }
    }

    private void releaseInventory(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return;
        }
        for (OrderItem item : order.getItems()) {
            Inventory inventory = null;
            if (item.getVariant() != null) {
                inventory = inventoryRepository.findByVariantIdWithLock(item.getVariant().getId()).orElse(null);
            }
            if (inventory == null) {
                inventory = inventoryRepository.findByProductIdWithLock(item.getProduct().getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Inventory not found for product: " + item.getProduct().getSku()));
            }

            inventory.setAvailableStock(inventory.getAvailableStock() + item.getQuantity());
            inventory.setReservedStock(Math.max(0, inventory.getReservedStock() - item.getQuantity()));
            inventoryRepository.save(inventory);
        }
    }

    private void fulfillInventory(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return;
        }
        for (OrderItem item : order.getItems()) {
            Inventory inventory = null;
            if (item.getVariant() != null) {
                inventory = inventoryRepository.findByVariantIdWithLock(item.getVariant().getId()).orElse(null);
            }
            if (inventory == null) {
                inventory = inventoryRepository.findByProductIdWithLock(item.getProduct().getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Inventory not found for product: " + item.getProduct().getSku()));
            }

            inventory.setReservedStock(Math.max(0, inventory.getReservedStock() - item.getQuantity()));
            inventoryRepository.save(inventory);
        }
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public OrderResponse updateOrderStatus(Long orderId, OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        OrderStatus oldStatus = order.getStatus();

        // Idempotency: skip transition if order is already in the target status
        if (oldStatus == newStatus) {
            log.info("Order #{} is already in status {}, skipping duplicate state change (idempotent)", order.getOrderNumber(), newStatus);
            return mapToOrderResponse(order);
        }

        validateTransition(oldStatus, newStatus);

        if (newStatus == OrderStatus.CANCELLED && oldStatus != OrderStatus.CANCELLED) {
            releaseInventory(order);
        } else if (newStatus == OrderStatus.COMPLETED && oldStatus != OrderStatus.COMPLETED) {
            fulfillInventory(order);
        }

        order.setStatus(newStatus);
        Order updatedOrder = orderRepository.save(order);

        String notifMsg = switch (newStatus) {
            case CONFIRMED -> "Payment received! Your order is confirmed and being prepared.";
            case SHIPPED -> "Your order has been dispatched with courier tracking.";
            case COMPLETED -> "Your order has been successfully delivered. Thank you!";
            case CANCELLED -> "Your order has been cancelled and any payment refunded.";
            default -> "Order status updated to: " + newStatus.name();
        };


        // Persist Order Event to Transactional Outbox (ORDER_CANCELLED triggers refund saga in payment-service)
        OrderEventPayload eventPayload = OrderEventPayload.builder()
                .eventType(newStatus == OrderStatus.CANCELLED ? "ORDER_CANCELLED" : "ORDER_STATUS_CHANGED")
                .orderId(updatedOrder.getId())
                .orderNumber(updatedOrder.getOrderNumber())
                .userId(updatedOrder.getUser().getId())
                .userEmail(updatedOrder.getUser().getEmail())
                .status(updatedOrder.getStatus())
                .totalAmount(updatedOrder.getTotalAmount())
                .shippingAddress(updatedOrder.getShippingAddress())
                .timestamp(LocalDateTime.now())
                .build();

        outboxService.saveEvent("ORDER", updatedOrder.getOrderNumber(), eventPayload.getEventType(), KafkaConfig.ORDER_EVENTS_TOPIC, eventPayload);

        return mapToOrderResponse(updatedOrder);
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public OrderResponse confirmPayment(Long orderId, Long paymentId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.PROCESSING ||
                order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.COMPLETED) {
            log.info("Idempotent duplicate event: Order #{} is already in status {}, skipping duplicate payment confirmation",
                    order.getOrderNumber(), order.getStatus());
            return mapToOrderResponse(order);
        }

        log.info("Saga Confirmation: Payment #{} confirmed for Order #{}", paymentId, orderId);
        return updateOrderStatus(orderId, OrderStatus.CONFIRMED);
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public OrderResponse shipOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.COMPLETED) {
            log.info("Idempotent duplicate event: Order #{} is already in status {}, skipping duplicate ship operation",
                    order.getOrderNumber(), order.getStatus());
            return mapToOrderResponse(order);
        }

        log.info("Saga Delivery: Marking Order #{} as SHIPPED", orderId);
        return updateOrderStatus(orderId, OrderStatus.SHIPPED);
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public OrderResponse completeOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getStatus() == OrderStatus.COMPLETED) {
            log.info("Idempotent duplicate event: Order #{} is already in status COMPLETED, skipping duplicate complete operation",
                    order.getOrderNumber());
            return mapToOrderResponse(order);
        }

        log.info("Saga Delivery: Marking Order #{} as COMPLETED", orderId);
        return updateOrderStatus(orderId, OrderStatus.COMPLETED);
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public OrderResponse handlePaymentFailure(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            log.info("Idempotent duplicate event: Order #{} is already CANCELLED, skipping duplicate payment failure",
                    order.getOrderNumber());
            return mapToOrderResponse(order);
        }

        log.warn("Saga Compensating Transaction: Payment failed for Order #{}. Reason: {}. Cancelling order and releasing stock.", orderId, reason);
        return updateOrderStatus(orderId, OrderStatus.CANCELLED);
    }

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public OrderResponse cancelOrder(Long orderId, String userEmail) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        if (!user.getRole().equals(Role.ROLE_ADMIN) && !order.getUser().getId().equals(user.getId())) {
            throw new BadRequestException("Unauthorized to cancel this order");
        }

        return updateOrderStatus(orderId, OrderStatus.CANCELLED);
    }

    private OrderResponse mapToOrderResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> OrderItemResponse.builder()
                        .id(item.getId())
                        .productId(item.getProduct().getId())
                        .productName(item.getProduct().getName())
                        .productSku(item.getSku() != null ? item.getSku() : item.getProduct().getSku())
                        .variantId(item.getVariant() != null ? item.getVariant().getId() : null)
                        .variantTitle(item.getVariantTitle() != null ? item.getVariantTitle() : (item.getVariant() != null ? item.getVariant().getTitle() : null))
                        .imageUrl(item.getVariant() != null ? item.getVariant().getEffectiveImageUrl() : (item.getProduct() != null ? item.getProduct().getImageUrl() : null))
                        .quantity(item.getQuantity())
                        .pricePerUnit(item.getPricePerUnit())
                        .subtotal(item.getSubtotal())
                        .build())
                .collect(Collectors.toList());

        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .userId(order.getUser().getId())
                .userEmail(order.getUser().getEmail())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .items(itemResponses)
                .shippingAddress(order.getShippingAddress())
                .estimatedDeliveryDate(order.getEstimatedDeliveryDate())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    /**
     * Automatic stock reclamation: Sweeps orders stuck in AWAITING_PAYMENT beyond the expiration window (15 mins)
     * and cancels them, immediately returning reserved inventory to available stock.
     */
    @Scheduled(fixedDelayString = "${order.unpaid-expiration.fixed-delay-ms:60000}")
    @Transactional
    public void expireUnpaidOrders() {
        LocalDateTime expirationThreshold = LocalDateTime.now().minusMinutes(15);
        List<Order> expiredOrders = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.AWAITING_PAYMENT, expirationThreshold);
        if (!expiredOrders.isEmpty()) {
            log.info("Stock Reclamation: Found {} expired unpaid orders awaiting cancellation", expiredOrders.size());
            for (Order order : expiredOrders) {
                try {
                    log.info("Auto-expiring unpaid Order #{} (placed at {})", order.getOrderNumber(), order.getCreatedAt());
                    updateOrderStatus(order.getId(), OrderStatus.CANCELLED);
                } catch (Exception e) {
                    log.error("Failed to auto-expire unpaid Order #{}: {}", order.getOrderNumber(), e.getMessage(), e);
                }
            }
        }
    }
}
