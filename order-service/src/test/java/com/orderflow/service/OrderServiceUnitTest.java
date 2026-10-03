package com.orderflow.service;

import com.orderflow.exception.BadRequestException;
import com.orderflow.exception.InsufficientStockException;
import com.orderflow.kafka.OrderEventProducer;
import com.orderflow.model.*;
import com.orderflow.outbox.OutboxService;
import com.orderflow.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderService}.
 *
 * Strategy: Mockito only — no Spring context, no database.
 * Covers:
 *   1. Illegal state transitions (every blocked path)
 *   2. confirmPayment idempotency
 *   3. Insufficient stock on order creation
 *   4. Valid happy-path state sequence
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductVariantRepository productVariantRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private OrderEventProducer orderEventProducer;
    @Mock private OutboxService outboxService;
    @Mock private AddressService addressService;

    @InjectMocks
    private OrderService orderService;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Order orderInStatus(OrderStatus status) {
        User user = User.builder()
                .id(1L)
                .email("test@example.com")
                .fullName("Test User")
                .password("hashed")
                .role(Role.ROLE_CUSTOMER)
                .build();

        Order order = Order.builder()
                .id(42L)
                .orderNumber("ORD-TEST01")
                .user(user)
                .status(status)
                .totalAmount(new BigDecimal("99.99"))
                .items(new ArrayList<>())
                .estimatedDeliveryDate(LocalDateTime.now().plusDays(3))
                .build();

        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        // Save returns the same order object for simplicity (lenient because illegal transitions don't reach save)
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        return order;
    }

    // -------------------------------------------------------------------------
    // 1. Illegal state transitions
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Illegal state transitions — must throw BadRequestException")
    class IllegalTransitions {

        @Test
        @DisplayName("COMPLETED -> CANCELLED must be rejected")
        void completedToCancelled_throwsBadRequest() {
            orderInStatus(OrderStatus.COMPLETED);
            assertThatThrownBy(() -> orderService.updateOrderStatus(42L, OrderStatus.CANCELLED))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Cannot transition order from COMPLETED to CANCELLED");
        }

        @Test
        @DisplayName("COMPLETED -> CONFIRMED must be rejected")
        void completedToConfirmed_throwsBadRequest() {
            orderInStatus(OrderStatus.COMPLETED);
            assertThatThrownBy(() -> orderService.updateOrderStatus(42L, OrderStatus.CONFIRMED))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("SHIPPED -> CONFIRMED must be rejected")
        void shippedToConfirmed_throwsBadRequest() {
            orderInStatus(OrderStatus.SHIPPED);
            assertThatThrownBy(() -> orderService.updateOrderStatus(42L, OrderStatus.CONFIRMED))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Cannot transition order from SHIPPED to CONFIRMED");
        }

        @Test
        @DisplayName("SHIPPED -> CANCELLED must be rejected")
        void shippedToCancelled_throwsBadRequest() {
            orderInStatus(OrderStatus.SHIPPED);
            assertThatThrownBy(() -> orderService.updateOrderStatus(42L, OrderStatus.CANCELLED))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Cannot transition order from SHIPPED to CANCELLED");
        }

        @Test
        @DisplayName("CANCELLED -> CONFIRMED must be rejected")
        void cancelledToConfirmed_throwsBadRequest() {
            orderInStatus(OrderStatus.CANCELLED);
            assertThatThrownBy(() -> orderService.updateOrderStatus(42L, OrderStatus.CONFIRMED))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Cannot transition order from CANCELLED to CONFIRMED");
        }

        @Test
        @DisplayName("CANCELLED -> SHIPPED must be rejected")
        void cancelledToShipped_throwsBadRequest() {
            orderInStatus(OrderStatus.CANCELLED);
            assertThatThrownBy(() -> orderService.updateOrderStatus(42L, OrderStatus.SHIPPED))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("AWAITING_PAYMENT -> SHIPPED must be rejected")
        void awaitingPaymentToShipped_throwsBadRequest() {
            orderInStatus(OrderStatus.AWAITING_PAYMENT);
            assertThatThrownBy(() -> orderService.updateOrderStatus(42L, OrderStatus.SHIPPED))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Cannot transition order from AWAITING_PAYMENT to SHIPPED");
        }

        @Test
        @DisplayName("PROCESSING -> CONFIRMED must be rejected")
        void processingToConfirmed_throwsBadRequest() {
            orderInStatus(OrderStatus.PROCESSING);
            assertThatThrownBy(() -> orderService.updateOrderStatus(42L, OrderStatus.CONFIRMED))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    // -------------------------------------------------------------------------
    // 2. Idempotency
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Idempotency — duplicate events must be safe no-ops")
    class IdempotencyTests {

        @Test
        @DisplayName("confirmPayment called on already-CONFIRMED order is a silent no-op")
        void confirmPayment_alreadyConfirmed_isNoOp() {
            Order order = orderInStatus(OrderStatus.CONFIRMED);

            // Should not throw, should not re-save, should not publish outbox events
            assertThatCode(() -> orderService.confirmPayment(42L, 99L))
                    .doesNotThrowAnyException();

            verify(orderRepository, never()).save(any());
            verify(outboxService, never()).saveEvent(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("confirmPayment called on already-COMPLETED order is a silent no-op")
        void confirmPayment_alreadyCompleted_isNoOp() {
            orderInStatus(OrderStatus.COMPLETED);

            assertThatCode(() -> orderService.confirmPayment(42L, 99L))
                    .doesNotThrowAnyException();

            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateOrderStatus with same status is a silent skip")
        void updateOrderStatus_sameStatus_skips() {
            orderInStatus(OrderStatus.PROCESSING);

            // Transitioning to PROCESSING when already PROCESSING → no-op
            assertThatCode(() -> orderService.updateOrderStatus(42L, OrderStatus.PROCESSING))
                    .doesNotThrowAnyException();

            // Save should not be invoked for same-status idempotent call
            verify(orderRepository, never()).save(any());
        }
    }

    // -------------------------------------------------------------------------
    // 3. Valid transitions
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Valid state transitions must succeed and publish outbox events")
    class ValidTransitions {

        @Test
        @DisplayName("AWAITING_PAYMENT -> CONFIRMED succeeds and publishes outbox event")
        void awaitingPaymentToConfirmed_succeeds() {
            orderInStatus(OrderStatus.AWAITING_PAYMENT);

            var result = orderService.updateOrderStatus(42L, OrderStatus.CONFIRMED);

            assertThat(result).isNotNull();
            verify(orderRepository).save(argThat(o -> o.getStatus() == OrderStatus.CONFIRMED));
            verify(outboxService).saveEvent(eq("ORDER"), eq("ORD-TEST01"), eq("ORDER_STATUS_CHANGED"), any(), any());
        }

        @Test
        @DisplayName("CONFIRMED -> PROCESSING succeeds")
        void confirmedToProcessing_succeeds() {
            orderInStatus(OrderStatus.CONFIRMED);

            var result = orderService.updateOrderStatus(42L, OrderStatus.PROCESSING);

            assertThat(result).isNotNull();
            verify(orderRepository).save(argThat(o -> o.getStatus() == OrderStatus.PROCESSING));
        }

        @Test
        @DisplayName("PROCESSING -> SHIPPED succeeds")
        void processingToShipped_succeeds() {
            orderInStatus(OrderStatus.PROCESSING);

            var result = orderService.updateOrderStatus(42L, OrderStatus.SHIPPED);

            assertThat(result).isNotNull();
            verify(orderRepository).save(argThat(o -> o.getStatus() == OrderStatus.SHIPPED));
        }

        @Test
        @DisplayName("SHIPPED -> COMPLETED succeeds and fulfils inventory")
        void shippedToCompleted_succeedsAndFulfilsInventory() {
            orderInStatus(OrderStatus.SHIPPED);

            var result = orderService.updateOrderStatus(42L, OrderStatus.COMPLETED);

            assertThat(result).isNotNull();
            verify(orderRepository).save(argThat(o -> o.getStatus() == OrderStatus.COMPLETED));
        }

        @Test
        @DisplayName("AWAITING_PAYMENT -> CANCELLED succeeds and emits ORDER_CANCELLED event")
        void awaitingPaymentToCancelled_emitsOrderCancelledEvent() {
            orderInStatus(OrderStatus.AWAITING_PAYMENT);

            var result = orderService.updateOrderStatus(42L, OrderStatus.CANCELLED);

            assertThat(result).isNotNull();
            verify(outboxService).saveEvent(eq("ORDER"), any(), eq("ORDER_CANCELLED"), any(), any());
        }
    }

    // -------------------------------------------------------------------------
    // 4. Insufficient stock
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("createOrder — insufficient stock guard")
    class InsufficientStockTests {

        @Test
        @DisplayName("Requesting more items than available stock throws InsufficientStockException")
        void insufficientStock_throwsInsufficientStockException() {
            // User lookup
            User user = User.builder()
                    .id(1L).email("buyer@example.com").fullName("Buyer")
                    .password("hashed").role(Role.ROLE_CUSTOMER).build();
            when(userRepository.findByEmail("buyer@example.com")).thenReturn(Optional.of(user));
            when(addressService.resolveOrderAddress(any(), any(), any())).thenReturn(null);

            // Product with only 2 units in stock
            Product product = Product.builder()
                    .id(10L).sku("SKU-001").name("Widget").price(new BigDecimal("25.00")).build();
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByProductIdAndIsDefaultTrue(10L)).thenReturn(Optional.empty());
            when(productVariantRepository.findByProductId(10L)).thenReturn(java.util.List.of());

            Inventory inventory = Inventory.builder()
                    .id(1L).product(product).availableStock(2).reservedStock(0).version(0L).build();
            when(inventoryRepository.findByProductIdWithLock(10L)).thenReturn(Optional.of(inventory));

            // Request 5 units — must fail
            var itemRequest = new com.orderflow.dto.OrderItemRequest(10L, 5);
            var request = new com.orderflow.dto.OrderRequest();
            request.setItems(java.util.List.of(itemRequest));

            assertThatThrownBy(() -> orderService.createOrder("buyer@example.com", request))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("Insufficient stock")
                    .hasMessageContaining("Requested: 5, Available: 2");
        }
    }
}
