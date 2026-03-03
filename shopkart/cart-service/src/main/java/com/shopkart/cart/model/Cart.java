package com.shopkart.cart.model;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cart implements Serializable {

    private Long userId;

    @Builder.Default
    private List<CartItem> items = new ArrayList<>();

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    // Applied coupon
    private String couponCode;
    private BigDecimal couponDiscount;

    public BigDecimal getTotalMrp() {
        return items.stream()
                .map(i -> i.getMrp().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getTotalPrice() {
        return items.stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getTotalDiscount() {
        BigDecimal productDiscount = getTotalMrp().subtract(getTotalPrice());
        BigDecimal coupon = couponDiscount != null ? couponDiscount : BigDecimal.ZERO;
        return productDiscount.add(coupon);
    }

    public BigDecimal getFinalAmount() {
        BigDecimal total = getTotalPrice();
        if (couponDiscount != null) {
            total = total.subtract(couponDiscount);
        }
        return total.max(BigDecimal.ZERO);
    }

    public int getTotalItems() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    public Optional<CartItem> findItem(Long productId) {
        return items.stream().filter(i -> i.getProductId().equals(productId)).findFirst();
    }

    public void addOrUpdateItem(CartItem newItem) {
        Optional<CartItem> existing = findItem(newItem.getProductId());
        if (existing.isPresent()) {
            existing.get().setQuantity(existing.get().getQuantity() + newItem.getQuantity());
        } else {
            items.add(newItem);
        }
        updatedAt = Instant.now();
    }

    public boolean removeItem(Long productId) {
        boolean removed = items.removeIf(i -> i.getProductId().equals(productId));
        if (removed) updatedAt = Instant.now();
        return removed;
    }

    public void clear() {
        items.clear();
        couponCode = null;
        couponDiscount = null;
        updatedAt = Instant.now();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartItem implements Serializable {
        private Long productId;
        private String productName;
        private String sku;
        private BigDecimal price;
        private BigDecimal mrp;
        private int quantity;
        private String thumbnailUrl;
        private int maxQuantityAllowed;
        private Instant addedAt;

        public BigDecimal getItemTotal() {
            return price.multiply(BigDecimal.valueOf(quantity));
        }

        public BigDecimal getItemSaving() {
            return mrp.subtract(price).multiply(BigDecimal.valueOf(quantity));
        }
    }
}
