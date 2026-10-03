package com.orderflow.service;

import com.orderflow.dto.GatewayOrderResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Slf4j
public class PaymentGatewayAdapter {

    @Value("${razorpay.key.id:rzp_test_OrderFlowKey2026}")
    private String keyId;

    @Value("${razorpay.key.secret:OrderFlowRazorpaySecretKey2026}")
    private String keySecret;

    public GatewayOrderResponse createGatewayOrder(Long orderId, String orderNumber, BigDecimal amount, String currency) {
        String curr = currency != null ? currency : "INR";
        long subunits = amount.multiply(BigDecimal.valueOf(100)).longValue();
        String gatewayOrderId = "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);

        log.info("Initialized Razorpay Gateway Order: [GatewayOrderId={}] for Internal Order #{} (Amount: {} {})",
                gatewayOrderId, orderNumber, amount, curr);

        return GatewayOrderResponse.builder()
                .gatewayOrderId(gatewayOrderId)
                .orderId(orderId)
                .orderNumber(orderNumber)
                .amount(amount)
                .amountInSubunits(subunits)
                .currency(curr)
                .keyId(keyId)
                .build();
    }

    public boolean verifySignature(String razorpayOrderId, String razorpayPaymentId, String receivedSignature) {
        if (razorpayOrderId == null || razorpayPaymentId == null || receivedSignature == null) {
            log.warn("Invalid signature parameters supplied for verification");
            return false;
        }

        try {
            String payload = razorpayOrderId + "|" + razorpayPaymentId;
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKey);
            byte[] hash = sha256Hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = HexFormat.of().formatHex(hash);

            boolean matches = MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    receivedSignature.trim().getBytes(StandardCharsets.UTF_8)
            );

            if (!matches) {
                log.warn("Razorpay HMAC signature mismatch! Expected: {}, Received: {}", expectedSignature, receivedSignature);
            } else {
                log.info("Razorpay cryptographic HMAC-SHA256 signature verified successfully for Payment: {}", razorpayPaymentId);
            }

            return matches;
        } catch (Exception e) {
            log.error("Error verifying Razorpay HMAC signature: {}", e.getMessage(), e);
            return false;
        }
    }

    public String computeSignatureForTest(String razorpayOrderId, String razorpayPaymentId) {
        try {
            String payload = razorpayOrderId + "|" + razorpayPaymentId;
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKey);
            return HexFormat.of().formatHex(sha256Hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute test signature", e);
        }
    }

    public String getKeyId() {
        return keyId;
    }
}
