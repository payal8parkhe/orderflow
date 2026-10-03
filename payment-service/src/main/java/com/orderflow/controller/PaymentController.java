package com.orderflow.controller;

import com.orderflow.dto.PaymentRequest;
import com.orderflow.dto.PaymentResponse;
import com.orderflow.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Asynchronous payment processing, gateway simulation, and refund management")
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "Process order payment", description = "Simulates charging customer credit card, UPI, or net banking, emitting Kafka payment event to confirm order")
    @PostMapping({"", "/process"})
    public ResponseEntity<PaymentResponse> processPayment(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody PaymentRequest request) {
        String username = userDetails != null ? userDetails.getUsername() : "customer@orderflow.io";
        PaymentResponse response = paymentService.processPayment(username, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get payment details for order", description = "Retrieve payment transaction details for a specified order")
    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentResponse> getPaymentByOrderId(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {
        String username = userDetails != null ? userDetails.getUsername() : "customer@orderflow.io";
        return ResponseEntity.ok(paymentService.getPaymentByOrderId(orderId, username));
    }

    @Operation(summary = "Create Razorpay gateway order", description = "Generates a gateway order for customer checkout")
    @PostMapping("/gateway/create-order")
    public ResponseEntity<com.orderflow.dto.GatewayOrderResponse> createGatewayOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody com.orderflow.dto.GatewayOrderRequest request) {
        String username = userDetails != null ? userDetails.getUsername() : "customer@orderflow.io";
        return ResponseEntity.ok(paymentService.createGatewayOrder(username, request));
    }

    @Operation(summary = "Verify Razorpay gateway payment signature", description = "Validates cryptographic HMAC-SHA256 signature and emits Kafka PAYMENT_COMPLETED event")
    @PostMapping("/gateway/verify")
    public ResponseEntity<PaymentResponse> verifyGatewayPayment(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody com.orderflow.dto.GatewayVerificationRequest request) {
        String username = userDetails != null ? userDetails.getUsername() : "customer@orderflow.io";
        return ResponseEntity.ok(paymentService.verifyGatewayPayment(username, request));
    }

    @Operation(summary = "Refund order payment", description = "Refund paid order and emit PAYMENT_REFUNDED Kafka event")
    @PostMapping("/refund/{orderId}")
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable Long orderId) {
        return ResponseEntity.ok(paymentService.refundPaymentIfPresent(orderId));
    }
}
