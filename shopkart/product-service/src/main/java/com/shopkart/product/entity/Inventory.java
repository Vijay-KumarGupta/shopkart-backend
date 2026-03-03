package com.shopkart.product.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "inventory")
@Getter @Setter
@Builder @NoArgsConstructor @AllArgsConstructor
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", unique = true)
    private Product product;

    @Column(nullable = false)
    @Builder.Default
    private int availableQuantity = 0;

    @Builder.Default
    private int reservedQuantity = 0;

    @Builder.Default
    private int lowStockThreshold = 10;

    @Version
    private Long version; // Optimistic locking for concurrent stock updates

    @UpdateTimestamp
    private Instant updatedAt;

    public int getTotalQuantity() {
        return availableQuantity + reservedQuantity;
    }

    public boolean isAvailable() {
        return availableQuantity > 0;
    }

    public boolean isLowStock() {
        return availableQuantity <= lowStockThreshold;
    }

    /**
     * Atomically reserve stock. Returns false if insufficient.
     */
    public boolean reserve(int quantity) {
        if (availableQuantity < quantity) return false;
        availableQuantity -= quantity;
        reservedQuantity += quantity;
        return true;
    }

    /**
     * Confirm reservation (order placed) - remove from reserved.
     */
    public void confirmReservation(int quantity) {
        reservedQuantity = Math.max(0, reservedQuantity - quantity);
    }

    /**
     * Release reservation back to available (order cancelled).
     */
    public void releaseReservation(int quantity) {
        reservedQuantity = Math.max(0, reservedQuantity - quantity);
        availableQuantity += quantity;
    }
}
