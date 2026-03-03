package com.shopkart.payment.service;

import com.shopkart.common.events.DomainEvents;
import com.shopkart.common.exception.ShopKartException;
import com.shopkart.payment.dto.PaymentDtos.*;
import com.shopkart.payment.entity.Payment;
import com.shopkart.payment.entity.Refund;
import com.shopkart.payment.gateway.PaymentGateway;
import com.shopkart.payment.gateway.PaymentGateway.GatewayResponse;
import com.shopkart.payment.repository.PaymentRepository;
import com.shopkart.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Initiates payment — creates a payment intent/order with the gateway.
     */
    @Transactional
    public InitiatePaymentResponse initiatePayment(Long userId, InitiatePaymentRequest request) {
        // Idempotency check
        if (paymentRepository.existsByOrderIdAndStatus(request.getOrderId(), Payment.PaymentStatus.PAID)) {
            throw new ShopKartException.DuplicateResourceException("Order already paid: " + request.getOrderId());
        }

        Payment payment = Payment.builder()
                .id("PAY-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase())
                .orderId(request.getOrderId())
                .userId(userId)
                .amount(request.getAmount())
                .method(Payment.PaymentMethod.valueOf(request.getPaymentMethod()))
                .status(Payment.PaymentStatus.INITIATED)
                .currency("INR")
                .build();

        Payment saved = paymentRepository.save(payment);

        // Create gateway order
        GatewayResponse gatewayOrder = paymentGateway.createOrder(
                saved.getId(), request.getAmount(), "INR");

        saved.setGatewayOrderId(gatewayOrder.getOrderId());
        paymentRepository.save(saved);

        log.info("Payment initiated: {} for order {}", saved.getId(), request.getOrderId());

        return InitiatePaymentResponse.builder()
                .paymentId(saved.getId())
                .gatewayOrderId(gatewayOrder.getOrderId())
                .amount(request.getAmount())
                .currency("INR")
                .build();
    }

    /**
     * Verifies payment after gateway callback — webhook or frontend confirmation.
     */
    @Transactional
    public PaymentResponse verifyPayment(VerifyPaymentRequest request) {
        Payment payment = paymentRepository.findById(request.getPaymentId())
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("Payment", request.getPaymentId()));

        if (payment.getStatus() == Payment.PaymentStatus.PAID) {
            return mapToPaymentResponse(payment); // Already verified, idempotent
        }

        // Verify with gateway
        boolean verified = paymentGateway.verifySignature(
                request.getGatewayOrderId(),
                request.getGatewayPaymentId(),
                request.getSignature());

        if (!verified) {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setFailureReason("Signature verification failed");
            paymentRepository.save(payment);

            kafkaTemplate.send("shopkart.payment.failed", payment.getOrderId(),
                    DomainEvents.PaymentFailedEvent.builder()
                            .paymentId(payment.getId())
                            .orderId(payment.getOrderId())
                            .userId(payment.getUserId())
                            .failureReason("Signature verification failed")
                            .failedAt(Instant.now())
                            .build());

            throw new ShopKartException.PaymentException("Payment verification failed");
        }

        // Payment successful
        payment.setStatus(Payment.PaymentStatus.PAID);
        payment.setGatewayPaymentId(request.getGatewayPaymentId());
        payment.setPaidAt(Instant.now());
        Payment saved = paymentRepository.save(payment);

        kafkaTemplate.send("shopkart.payment.completed", payment.getOrderId(),
                DomainEvents.PaymentCompletedEvent.builder()
                        .paymentId(saved.getId())
                        .orderId(saved.getOrderId())
                        .userId(saved.getUserId())
                        .amount(saved.getAmount())
                        .paymentMethod(saved.getMethod().name())
                        .transactionId(request.getGatewayPaymentId())
                        .completedAt(saved.getPaidAt())
                        .build());

        log.info("Payment verified: {} for order {}", saved.getId(), saved.getOrderId());
        return mapToPaymentResponse(saved);
    }

    /**
     * Refund processing — triggered on order cancellation.
     */
    @Transactional
    @KafkaListener(topics = "shopkart.order.cancelled", groupId = "payment-service")
    public void processRefund(DomainEvents.OrderCancelledEvent event) {
        paymentRepository.findByOrderId(event.getOrderId()).ifPresent(payment -> {
            if (payment.getStatus() != Payment.PaymentStatus.PAID) return;

            String refundId = "REF-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();

            // Initiate gateway refund
            try {
                paymentGateway.initiateRefund(payment.getGatewayPaymentId(), payment.getAmount(), refundId);

                Refund refund = Refund.builder()
                        .id(refundId)
                        .payment(payment)
                        .amount(payment.getAmount())
                        .reason(event.getReason())
                        .status(Refund.RefundStatus.PROCESSED)
                        .processedAt(Instant.now())
                        .build();

                refundRepository.save(refund);

                payment.setStatus(Payment.PaymentStatus.REFUNDED);
                paymentRepository.save(payment);

                kafkaTemplate.send("shopkart.payment.refunded", event.getOrderId(),
                        DomainEvents.PaymentRefundedEvent.builder()
                                .refundId(refundId)
                                .orderId(event.getOrderId())
                                .userId(event.getUserId())
                                .refundAmount(payment.getAmount())
                                .refundedAt(Instant.now())
                                .build());

                log.info("Refund {} processed for order {} amount ₹{}",
                        refundId, event.getOrderId(), payment.getAmount());

            } catch (Exception e) {
                log.error("Refund failed for order {}: {}", event.getOrderId(), e.getMessage());
                // Schedule retry via DLQ
            }
        });
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByOrderId(String orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("Payment for order", orderId));
        return mapToPaymentResponse(payment);
    }

    private PaymentResponse mapToPaymentResponse(Payment p) {
        return PaymentResponse.builder()
                .id(p.getId())
                .orderId(p.getOrderId())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .method(p.getMethod().name())
                .status(p.getStatus().name())
                .gatewayPaymentId(p.getGatewayPaymentId())
                .paidAt(p.getPaidAt())
                .build();
    }
}
