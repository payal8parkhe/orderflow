package com.orderflow.service;

import com.orderflow.dto.OrderResponse;
import com.orderflow.dto.PaymentEventPayload;
import com.orderflow.dto.PaymentRequest;
import com.orderflow.dto.PaymentResponse;
import com.orderflow.exception.BadRequestException;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.kafka.PaymentEventProducer;
import com.orderflow.model.Payment;
import com.orderflow.model.PaymentMethod;
import com.orderflow.model.PaymentStatus;
import com.orderflow.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import com.orderflow.dto.GatewayOrderRequest;
import com.orderflow.dto.GatewayOrderResponse;
import com.orderflow.dto.GatewayVerificationRequest;
import com.orderflow.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventProducer paymentEventProducer;
    private final PaymentGatewayAdapter paymentGatewayAdapter;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${order.service.url:http://localhost:8081}")
    private String orderServiceUrl;

    public OrderResponse fetchRemoteOrder(Long orderId) {
        String authHeader = null;
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                authHeader = req.getHeader("Authorization");
            }
        } catch (Exception ignored) {
        }

        if (authHeader == null && jwtTokenProvider != null) {
            authHeader = "Bearer " + jwtTokenProvider.generateToken("system-payment-service", "ROLE_ADMIN");
        }

        try {
            RestClient.RequestHeadersSpec<?> reqSpec = RestClient.create().get()
                    .uri(orderServiceUrl + "/api/orders/" + orderId);
            if (authHeader != null) {
                reqSpec.header("Authorization", authHeader);
            }
            ResponseEntity<OrderResponse> response = reqSpec.retrieve().toEntity(OrderResponse.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.error("Failed to fetch order #{} from order-service: {}", orderId, e.getMessage());
            throw new BadRequestException("Failed to verify order details with order-service: " + e.getMessage());
        }
        throw new BadRequestException("Order #" + orderId + " could not be resolved from order-service");
    }

    @Transactional
    public PaymentResponse processPayment(String userEmail, PaymentRequest request) {
        Optional<Payment> existingOpt = paymentRepository.findByOrderId(request.getOrderId());
        if (existingOpt.isPresent() && existingOpt.get().getStatus() == PaymentStatus.COMPLETED) {
            throw new BadRequestException("Payment has already been completed for order #" + existingOpt.get().getOrderNumber());
        }

        Payment payment = existingOpt.orElseGet(() -> {
            OrderResponse ord = fetchRemoteOrder(request.getOrderId());

            if (request.getAmount() != null && request.getAmount().compareTo(ord.getTotalAmount()) != 0) {
                throw new BadRequestException(String.format("Payment amount %s does not match order total %s",
                        request.getAmount(), ord.getTotalAmount()));
            }

            return Payment.builder()
                    .orderId(ord.getId())
                    .orderNumber(ord.getOrderNumber())
                    .userId(ord.getUserId())
                    .userEmail(userEmail)
                    .amount(ord.getTotalAmount())
                    .currency("INR")
                    .status(PaymentStatus.PENDING)
                    .paymentMethod(request.getPaymentMethod())
                    .transactionReference("PENDING-" + UUID.randomUUID().toString().substring(0, 8))
                    .build();
        });

        String txnRef = "TXN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        boolean simulateFail = Boolean.TRUE.equals(request.getSimulateFailure());

        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setTransactionReference(txnRef);

        if (request.getPaymentMethod() == PaymentMethod.CASH_ON_DELIVERY) {
            payment.setStatus(PaymentStatus.PENDING);
            payment.setPaidAt(null);
            payment.setTransactionReference("COD-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase());
            Payment saved = paymentRepository.save(payment);

            log.info("Order #{} placed with Cash on Delivery (COD). Emitting PAYMENT_COD_CONFIRMED.", saved.getOrderNumber());

            paymentEventProducer.sendPaymentEvent(PaymentEventPayload.builder()
                    .eventType("PAYMENT_COD_CONFIRMED")
                    .paymentId(saved.getId())
                    .orderId(saved.getOrderId())
                    .orderNumber(saved.getOrderNumber())
                    .userId(saved.getUserId())
                    .userEmail(saved.getUserEmail())
                    .amount(saved.getAmount())
                    .status(PaymentStatus.PENDING)
                    .timestamp(LocalDateTime.now())
                    .build());

            return mapToResponse(saved);
        }

        if (simulateFail) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setPaidAt(null);
            Payment saved = paymentRepository.save(payment);

            log.warn("Payment simulation failure triggered for Order #{}", saved.getOrderNumber());

            paymentEventProducer.sendPaymentEvent(PaymentEventPayload.builder()
                    .eventType("PAYMENT_FAILED")
                    .paymentId(saved.getId())
                    .orderId(saved.getOrderId())
                    .orderNumber(saved.getOrderNumber())
                    .userId(saved.getUserId())
                    .userEmail(saved.getUserEmail())
                    .amount(saved.getAmount())
                    .status(PaymentStatus.FAILED)
                    .failureReason("Simulated card decline or gateway timeout")
                    .timestamp(LocalDateTime.now())
                    .build());

            return mapToResponse(saved);
        }

        // Payment Succeeded
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setPaidAt(LocalDateTime.now());
        Payment saved = paymentRepository.save(payment);

        log.info("Payment succeeded: Txn #{} for Order #{} amount ₹{}", txnRef, saved.getOrderNumber(), saved.getAmount());

        paymentEventProducer.sendPaymentEvent(PaymentEventPayload.builder()
                .eventType("PAYMENT_COMPLETED")
                .paymentId(saved.getId())
                .orderId(saved.getOrderId())
                .orderNumber(saved.getOrderNumber())
                .userId(saved.getUserId())
                .userEmail(saved.getUserEmail())
                .amount(saved.getAmount())
                .status(PaymentStatus.COMPLETED)
                .timestamp(LocalDateTime.now())
                .build());

        return mapToResponse(saved);
    }

    @Transactional
    public void recordPendingOrder(Long orderId, String orderNumber, Long userId, String userEmail, BigDecimal amount) {
        if (paymentRepository.findByOrderId(orderId).isEmpty()) {
            Payment payment = Payment.builder()
                    .orderId(orderId)
                    .orderNumber(orderNumber)
                    .userId(userId)
                    .userEmail(userEmail)
                    .amount(amount)
                    .currency("INR")
                    .paymentMethod(PaymentMethod.CREDIT_CARD)
                    .status(PaymentStatus.PENDING)
                    .transactionReference("PENDING-" + UUID.randomUUID().toString().substring(0, 8))
                    .build();
            paymentRepository.save(payment);
            log.info("Pre-initialized payment ledger for Order #{}", orderNumber);
        }
    }

    @Transactional
    public void completeCodPaymentIfPending(Long orderId) {
        paymentRepository.findByOrderId(orderId).ifPresent(payment -> {
            if (payment.getPaymentMethod() == PaymentMethod.CASH_ON_DELIVERY && payment.getStatus() == PaymentStatus.PENDING) {
                payment.setStatus(PaymentStatus.COMPLETED);
                payment.setPaidAt(LocalDateTime.now());
                payment.setTransactionReference("COD-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase());
                Payment saved = paymentRepository.save(payment);
                log.info("Cash on Delivery payment settled upon doorstep delivery: Txn #{} for Order #{} amount ₹{}",
                        saved.getTransactionReference(), saved.getOrderNumber(), saved.getAmount());
            }
        });
    }

    @Transactional
    public PaymentResponse refundPaymentIfPresent(Long orderId) {
        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(orderId);
        if (paymentOpt.isEmpty()) {
            return null;
        }

        Payment payment = paymentOpt.get();

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Idempotent duplicate event: Payment for order #{} is already REFUNDED (Ref: {}). No-op.",
                    orderId, payment.getRefundReference());
            return mapToResponse(payment);
        }

        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setRefundReference(null);
            Payment saved = paymentRepository.save(payment);
            log.info("Pending uncollected payment marked CANCELLED (no refund needed) for order #{}", orderId);
            return mapToResponse(saved);
        }

        String refundRef = "REF-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundReference(refundRef);
        Payment saved = paymentRepository.save(payment);

        log.info("Refund recorded and event emitted for order #{} (Refund Ref: {})", orderId, refundRef);

        paymentEventProducer.sendPaymentEvent(PaymentEventPayload.builder()
                .eventType("PAYMENT_REFUNDED")
                .paymentId(saved.getId())
                .orderId(saved.getOrderId())
                .orderNumber(saved.getOrderNumber())
                .userId(saved.getUserId())
                .userEmail(saved.getUserEmail())
                .amount(saved.getAmount())
                .status(PaymentStatus.REFUNDED)
                .refundReference(refundRef)
                .timestamp(LocalDateTime.now())
                .build());

        return mapToResponse(saved);
    }

    @Transactional
    public GatewayOrderResponse createGatewayOrder(String userEmail, GatewayOrderRequest request) {
        OrderResponse ord = fetchRemoteOrder(request.getOrderId());
        String orderNum = ord.getOrderNumber();
        BigDecimal amount = ord.getTotalAmount();
        Long userId = ord.getUserId();

        GatewayOrderResponse gatewayOrder = paymentGatewayAdapter.createGatewayOrder(
                request.getOrderId(), orderNum, amount, request.getCurrency()
        );

        Optional<Payment> existingOpt = paymentRepository.findByOrderId(request.getOrderId());
        final String finalOrderNum = orderNum;
        final BigDecimal finalAmount = amount;
        final Long finalUserId = userId;
        Payment payment = existingOpt.orElseGet(() -> Payment.builder()
                .orderId(request.getOrderId())
                .orderNumber(finalOrderNum)
                .userId(finalUserId)
                .userEmail(userEmail)
                .amount(finalAmount)
                .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
                .status(PaymentStatus.PENDING)
                .build());

        payment.setTransactionReference("INIT-" + gatewayOrder.getGatewayOrderId());
        paymentRepository.save(payment);

        return gatewayOrder;
    }

    @Transactional
    public PaymentResponse verifyGatewayPayment(String userEmail, GatewayVerificationRequest request) {
        boolean valid = paymentGatewayAdapter.verifySignature(
                request.getRazorpayOrderId(),
                request.getRazorpayPaymentId(),
                request.getRazorpaySignature()
        );

        if (!valid) {
            throw new BadRequestException("Invalid payment signature. Razorpay cryptographic verification failed.");
        }

        Payment payment = paymentRepository.findByOrderId(request.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for order id: " + request.getOrderId()));

        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            return mapToResponse(payment);
        }

        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setPaymentMethod(PaymentMethod.CREDIT_CARD);
        payment.setTransactionReference(request.getRazorpayPaymentId());
        payment.setPaidAt(LocalDateTime.now());
        Payment saved = paymentRepository.save(payment);

        log.info("Razorpay Payment verified and COMPLETED for order #{} (Txn: {})",
                saved.getOrderNumber(), saved.getTransactionReference());

        // Emit Kafka PAYMENT_COMPLETED event to confirm order in order-service
        paymentEventProducer.sendPaymentEvent(PaymentEventPayload.builder()
                .eventType("PAYMENT_COMPLETED")
                .paymentId(saved.getId())
                .orderId(saved.getOrderId())
                .orderNumber(saved.getOrderNumber())
                .userId(saved.getUserId())
                .userEmail(saved.getUserEmail())
                .amount(saved.getAmount())
                .status(saved.getStatus())
                .transactionReference(saved.getTransactionReference())
                .timestamp(LocalDateTime.now())
                .build());

        return mapToResponse(saved);
    }

    public PaymentResponse getPaymentByOrderId(Long orderId, String userEmail) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment record not found for order id: " + orderId));
        return mapToResponse(payment);
    }

    private PaymentResponse mapToResponse(Payment payment) {
        return PaymentResponse.builder()
                .id(payment.getId())
                .orderId(payment.getOrderId())
                .orderNumber(payment.getOrderNumber())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .transactionReference(payment.getTransactionReference())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .refundReference(payment.getRefundReference())
                .paidAt(payment.getPaidAt())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
