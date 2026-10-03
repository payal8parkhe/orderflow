package com.orderflow.service;

import com.orderflow.dto.OrderResponse;
import com.orderflow.dto.PaymentRequest;
import com.orderflow.exception.BadRequestException;
import com.orderflow.kafka.PaymentEventProducer;
import com.orderflow.model.*;
import com.orderflow.repository.PaymentRepository;
import com.orderflow.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PaymentService}.
 *
 * Covers the critical correctness fixes identified in the code review:
 *
 *   1. Order lookup failure must throw {@link BadRequestException} — never silently
 *      fall back to a hardcoded $149.99 charge or dummy userId = 1L.
 *
 *   2. Amount mismatch (client sends explicit amount ≠ order total) must be rejected
 *      before any payment record is created.
 *
 *   3. Duplicate payment (already COMPLETED) must be rejected with a clear message.
 *
 *   4. Matching amount succeeds and the payment record is persisted with the
 *      order's actual total — not a hardcoded value.
 *
 * The {@link PaymentService#fetchRemoteOrder} method is spied and stubbed to
 * avoid real HTTP calls in unit tests.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentEventProducer paymentEventProducer;
    @Mock private PaymentGatewayAdapter paymentGatewayAdapter;
    @Mock private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    @Spy
    private PaymentService paymentService;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private OrderResponse orderWith(Long id, String orderNumber, BigDecimal total, Long userId) {
        return OrderResponse.builder()
                .id(id)
                .orderNumber(orderNumber)
                .userId(userId)
                .userEmail("user@example.com")
                .status(OrderStatus.AWAITING_PAYMENT)
                .totalAmount(total)
                .items(java.util.List.of())
                .build();
    }

    private Payment pendingPayment(Long orderId, BigDecimal amount) {
        return Payment.builder()
                .id(1L)
                .orderId(orderId)
                .orderNumber("ORD-001")
                .userId(10L)
                .userEmail("user@example.com")
                .amount(amount)
                .currency("INR")
                .status(PaymentStatus.PENDING)
                .paymentMethod(PaymentMethod.CREDIT_CARD)
                .transactionReference("PENDING-ABCD")
                .build();
    }

    // -----------------------------------------------------------------------
    // 1. Order lookup failure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Order lookup failure — never fall back to hardcoded price")
    class OrderLookupFailure {

        @Test
        @DisplayName("fetchRemoteOrder throws BadRequestException when order-service is unreachable")
        void fetchRemoteOrder_unreachable_throwsBadRequest() {
            // Simulate network failure from fetchRemoteOrder
            doThrow(new BadRequestException("Failed to verify order details with order-service: Connection refused"))
                    .when(paymentService).fetchRemoteOrder(99L);

            when(paymentRepository.findByOrderId(99L)).thenReturn(Optional.empty());

            PaymentRequest request = PaymentRequest.builder()
                    .orderId(99L)
                    .paymentMethod(PaymentMethod.CREDIT_CARD)
                    .build();

            assertThatThrownBy(() -> paymentService.processPayment("user@example.com", request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Failed to verify order details with order-service");

            // Verify: no payment record was persisted
            verify(paymentRepository, never()).save(any());
        }

        @Test
        @DisplayName("fetchRemoteOrder throws BadRequestException when order-service returns empty body")
        void fetchRemoteOrder_emptyBody_throwsBadRequest() {
            doThrow(new BadRequestException("Order #77 could not be resolved from order-service"))
                    .when(paymentService).fetchRemoteOrder(77L);

            when(paymentRepository.findByOrderId(77L)).thenReturn(Optional.empty());

            PaymentRequest request = PaymentRequest.builder()
                    .orderId(77L)
                    .paymentMethod(PaymentMethod.CREDIT_CARD)
                    .build();

            assertThatThrownBy(() -> paymentService.processPayment("user@example.com", request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("could not be resolved");

            verify(paymentRepository, never()).save(any());
        }
    }

    // -----------------------------------------------------------------------
    // 2. Amount mismatch rejection
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Amount mismatch — client-supplied amount must equal order total")
    class AmountMismatch {

        @Test
        @DisplayName("Client sends ₹50 but order total is ₹99.99 — must throw BadRequestException")
        void amountMismatch_throwsBadRequest() {
            BigDecimal orderTotal = new BigDecimal("99.99");
            BigDecimal clientAmount = new BigDecimal("50.00");

            doReturn(orderWith(5L, "ORD-005", orderTotal, 10L))
                    .when(paymentService).fetchRemoteOrder(5L);

            when(paymentRepository.findByOrderId(5L)).thenReturn(Optional.empty());

            PaymentRequest request = PaymentRequest.builder()
                    .orderId(5L)
                    .amount(clientAmount) // explicit — but wrong
                    .paymentMethod(PaymentMethod.CREDIT_CARD)
                    .build();

            assertThatThrownBy(() -> paymentService.processPayment("user@example.com", request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Payment amount 50.00 does not match order total 99.99");

            verify(paymentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Client sends amount matching order total — succeeds")
        void correctAmount_succeeds() {
            BigDecimal orderTotal = new BigDecimal("99.99");

            doReturn(orderWith(6L, "ORD-006", orderTotal, 10L))
                    .when(paymentService).fetchRemoteOrder(6L);

            when(paymentRepository.findByOrderId(6L)).thenReturn(Optional.empty());
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
                Payment p = inv.getArgument(0);
                p.setId(100L);
                return p;
            });

            PaymentRequest request = PaymentRequest.builder()
                    .orderId(6L)
                    .amount(orderTotal) // exactly matching — must pass
                    .paymentMethod(PaymentMethod.CREDIT_CARD)
                    .build();

            var response = paymentService.processPayment("user@example.com", request);

            assertThat(response).isNotNull();
            assertThat(response.getAmount()).isEqualByComparingTo(orderTotal);
        }

        @Test
        @DisplayName("Client sends no amount (null) — defaults to order total without rejection")
        void nullAmount_usesOrderTotal() {
            BigDecimal orderTotal = new BigDecimal("79.50");

            doReturn(orderWith(7L, "ORD-007", orderTotal, 10L))
                    .when(paymentService).fetchRemoteOrder(7L);

            when(paymentRepository.findByOrderId(7L)).thenReturn(Optional.empty());
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
                Payment p = inv.getArgument(0);
                p.setId(101L);
                return p;
            });

            PaymentRequest request = PaymentRequest.builder()
                    .orderId(7L)
                    .amount(null) // not supplied — should use order total
                    .paymentMethod(PaymentMethod.CREDIT_CARD)
                    .build();

            var response = paymentService.processPayment("user@example.com", request);

            assertThat(response.getAmount()).isEqualByComparingTo(orderTotal);
        }
    }

    // -----------------------------------------------------------------------
    // 3. Duplicate COMPLETED payment rejection
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Duplicate payment protection")
    class DuplicatePayment {

        @Test
        @DisplayName("Attempting to pay an already-COMPLETED order throws BadRequestException")
        void alreadyCompleted_throwsBadRequest() {
            Payment existing = Payment.builder()
                    .id(5L)
                    .orderId(55L)
                    .orderNumber("ORD-ALREADY")
                    .status(PaymentStatus.COMPLETED)
                    .amount(new BigDecimal("99.99"))
                    .currency("INR")
                    .paymentMethod(PaymentMethod.CREDIT_CARD)
                    .transactionReference("TXN-DONE")
                    .build();

            when(paymentRepository.findByOrderId(55L)).thenReturn(Optional.of(existing));

            PaymentRequest request = PaymentRequest.builder()
                    .orderId(55L)
                    .paymentMethod(PaymentMethod.CREDIT_CARD)
                    .build();

            assertThatThrownBy(() -> paymentService.processPayment("user@example.com", request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Payment has already been completed");
        }
    }

    // -----------------------------------------------------------------------
    // 4. Simulation failure path
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Payment simulation (simulateFailure=true)")
    class SimulationTests {

        @Test
        @DisplayName("simulateFailure=true marks payment FAILED and emits PAYMENT_FAILED event")
        void simulateFailure_marksPaymentFailed() {
            BigDecimal orderTotal = new BigDecimal("59.00");

            doReturn(orderWith(8L, "ORD-008", orderTotal, 10L))
                    .when(paymentService).fetchRemoteOrder(8L);

            when(paymentRepository.findByOrderId(8L)).thenReturn(Optional.empty());
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
                Payment p = inv.getArgument(0);
                p.setId(200L);
                return p;
            });

            PaymentRequest request = PaymentRequest.builder()
                    .orderId(8L)
                    .paymentMethod(PaymentMethod.CREDIT_CARD)
                    .simulateFailure(true)
                    .build();

            var response = paymentService.processPayment("user@example.com", request);

            assertThat(response.getStatus()).isEqualTo(PaymentStatus.FAILED);
            verify(paymentEventProducer).sendPaymentEvent(argThat(e ->
                    "PAYMENT_FAILED".equals(e.getEventType())));
        }
    }
}
