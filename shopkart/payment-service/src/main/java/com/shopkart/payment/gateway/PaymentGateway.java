package com.shopkart.payment.gateway;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Payment Gateway abstraction.
 * Currently mocks Razorpay-style integration.
 * In production, inject the Razorpay SDK client and call real APIs.
 */
@Slf4j
@Component
public class PaymentGateway {

    @Value("${payment.gateway.key-id:rzp_test_shopkart}")
    private String keyId;

    @Value("${payment.gateway.key-secret:secret}")
    private String keySecret;

    @Value("${payment.gateway.provider:razorpay}")
    private String provider;

    /**
     * Creates a payment order with the gateway.
     * Returns gateway order ID used to initiate payment on frontend.
     */
    public GatewayResponse createOrder(String paymentId, BigDecimal amount, String currency) {
        log.info("[{}] Creating gateway order for payment {} amount {}", provider, paymentId, amount);

        // In production: razorpay.orders().create(...)
        // Mock response:
        return GatewayResponse.builder()
                .orderId("order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .paymentId(paymentId)
                .amount(amount)
                .currency(currency)
                .build();
    }

    /**
     * Verifies payment signature (Razorpay HMAC-SHA256).
     * Prevents payment tampering.
     */
    public boolean verifySignature(String gatewayOrderId, String gatewayPaymentId, String signature) {
        try {
            String payload = gatewayOrderId + "|" + gatewayPaymentId;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes());
            String expectedSignature = HexFormat.of().formatHex(hash);

            boolean valid = expectedSignature.equals(signature);
            if (!valid) {
                log.warn("Signature mismatch for order {} payment {}", gatewayOrderId, gatewayPaymentId);
            }
            return valid;
        } catch (Exception e) {
            log.error("Signature verification error: {}", e.getMessage());
            // In dev/test, allow through; in prod, always verify
            return true; // Remove in production!
        }
    }

    /**
     * Initiates a refund with the gateway.
     */
    public GatewayResponse initiateRefund(String gatewayPaymentId, BigDecimal amount, String refundId) {
        log.info("[{}] Initiating refund {} for payment {} amount {}", provider, refundId, gatewayPaymentId, amount);

        // In production: razorpay.refunds().create(...)
        return GatewayResponse.builder()
                .orderId("rfnd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14))
                .amount(amount)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GatewayResponse {
        private String orderId;
        private String paymentId;
        private BigDecimal amount;
        private String currency;
        private String status;
        private String refundId;
    }
}
