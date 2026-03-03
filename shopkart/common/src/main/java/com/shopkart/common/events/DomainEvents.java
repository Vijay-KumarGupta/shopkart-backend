package com.shopkart.common.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * All domain events published to Kafka topics.
 * Topic naming convention: shopkart.{domain}.{event}
 */
public final class DomainEvents {

    private DomainEvents() {}

    // ===== BASE EVENT =====
    @Data
    public abstract static class BaseEvent {
        private final String eventId = UUID.randomUUID().toString();
        private final Instant occurredAt = Instant.now();
        private final String eventType;

        protected BaseEvent(String eventType) {
            this.eventType = eventType;
        }
    }

    // ===== USER EVENTS =====
    // Topic: shopkart.user.registered
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserRegisteredEvent {
        private Long userId;
        private String email;
        private String name;
        private String phone;
        private Instant registeredAt;
    }

    // Topic: shopkart.user.login
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserLoginEvent {
        private Long userId;
        private String email;
        private String ipAddress;
        private String deviceType;
        private Instant loginAt;
    }

    // ===== ORDER EVENTS =====
    // Topic: shopkart.order.placed
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderPlacedEvent {
        private String orderId;
        private Long userId;
        private List<OrderItemEvent> items;
        private BigDecimal totalAmount;
        private String shippingAddressId;
        private String paymentMethod;
        private Instant placedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemEvent {
        private Long productId;
        private String productName;
        private int quantity;
        private BigDecimal price;
    }

    // Topic: shopkart.order.confirmed
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderConfirmedEvent {
        private String orderId;
        private Long userId;
        private String email;
        private BigDecimal totalAmount;
        private Instant confirmedAt;
    }

    // Topic: shopkart.order.shipped
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderShippedEvent {
        private String orderId;
        private Long userId;
        private String email;
        private String trackingNumber;
        private String courierPartner;
        private Instant shippedAt;
        private Instant estimatedDelivery;
    }

    // Topic: shopkart.order.delivered
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderDeliveredEvent {
        private String orderId;
        private Long userId;
        private Instant deliveredAt;
    }

    // Topic: shopkart.order.cancelled
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderCancelledEvent {
        private String orderId;
        private Long userId;
        private String reason;
        private List<OrderItemEvent> items;
        private Instant cancelledAt;
    }

    // ===== PAYMENT EVENTS =====
    // Topic: shopkart.payment.completed
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentCompletedEvent {
        private String paymentId;
        private String orderId;
        private Long userId;
        private BigDecimal amount;
        private String paymentMethod;
        private String transactionId;
        private Instant completedAt;
    }

    // Topic: shopkart.payment.failed
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentFailedEvent {
        private String paymentId;
        private String orderId;
        private Long userId;
        private String failureReason;
        private Instant failedAt;
    }

    // Topic: shopkart.payment.refunded
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentRefundedEvent {
        private String refundId;
        private String orderId;
        private Long userId;
        private BigDecimal refundAmount;
        private Instant refundedAt;
    }

    // ===== INVENTORY EVENTS =====
    // Topic: shopkart.inventory.low
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventoryLowEvent {
        private Long productId;
        private String productName;
        private int currentStock;
        private int threshold;
        private Instant occurredAt;
    }

    // Topic: shopkart.inventory.reserved
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventoryReservedEvent {
        private String orderId;
        private List<OrderItemEvent> items;
        private boolean success;
        private String failureReason;
        private Instant occurredAt;
    }
}
