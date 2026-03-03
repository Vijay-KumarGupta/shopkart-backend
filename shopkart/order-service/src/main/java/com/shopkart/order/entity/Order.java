package com.shopkart.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders",
       indexes = {
           @Index(name = "idx_order_user", columnList = "user_id"),
           @Index(name = "idx_order_status", columnList = "status"),
           @Index(name = "idx_order_placed_at", columnList = "placed_at")
       })
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    private String id; // e.g. ORD-2024-XXXX for readability

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalMrp;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalDiscount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal deliveryCharge;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    // Snapshot of shipping address at time of order
    @Embedded
    private ShippingAddress shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    private String paymentId;
    private String trackingNumber;
    private String courierPartner;

    private String couponCode;
    private BigDecimal couponDiscount;

    private String cancellationReason;
    private Instant cancelledAt;

    @Column(name = "placed_at")
    @CreationTimestamp
    private Instant placedAt;

    private Instant confirmedAt;
    private Instant shippedAt;
    private Instant deliveredAt;
    private Instant estimatedDelivery;

    @UpdateTimestamp
    private Instant updatedAt;

    @Version
    private Long version;

    // ===== STATUS TRANSITIONS =====
    public void confirm() {
        if (status != OrderStatus.PENDING) throw new IllegalStateException("Cannot confirm order in state: " + status);
        status = OrderStatus.CONFIRMED;
        paymentStatus = PaymentStatus.PAID;
        confirmedAt = Instant.now();
    }

    public void ship(String trackingNum, String courier, Instant estimatedDelivery) {
        if (status != OrderStatus.CONFIRMED) throw new IllegalStateException("Cannot ship order in state: " + status);
        status = OrderStatus.SHIPPED;
        trackingNumber = trackingNum;
        courierPartner = courier;
        shippedAt = Instant.now();
        this.estimatedDelivery = estimatedDelivery;
    }

    public void deliver() {
        if (status != OrderStatus.SHIPPED) throw new IllegalStateException("Cannot deliver order in state: " + status);
        status = OrderStatus.DELIVERED;
        deliveredAt = Instant.now();
    }

    public void cancel(String reason) {
        if (status == OrderStatus.DELIVERED) throw new IllegalStateException("Cannot cancel a delivered order");
        if (status == OrderStatus.CANCELLED) throw new IllegalStateException("Order already cancelled");
        status = OrderStatus.CANCELLED;
        cancellationReason = reason;
        cancelledAt = Instant.now();
    }

    public enum OrderStatus {
        PENDING, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, CANCELLED, RETURN_REQUESTED, RETURNED
    }

    public enum PaymentMethod { CARD, UPI, NET_BANKING, WALLET, COD, EMI }
    public enum PaymentStatus { PENDING, PAID, FAILED, REFUNDED, PARTIALLY_REFUNDED }

    @Embeddable
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ShippingAddress {
        private String fullName;
        private String phone;
        private String addressLine1;
        private String addressLine2;
        private String city;
        private String state;
        private String pincode;
        private String country;
    }
}
