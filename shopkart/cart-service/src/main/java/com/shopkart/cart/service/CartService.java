package com.shopkart.cart.service;

import com.shopkart.cart.model.Cart;
import com.shopkart.cart.model.Cart.CartItem;
import com.shopkart.common.exception.ShopKartException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CART_KEY_PREFIX = "cart:user:";
    private static final long CART_TTL_DAYS = 30;
    private static final int MAX_ITEMS_PER_CART = 50;
    private static final int MAX_QUANTITY_PER_ITEM = 20;

    // ===== CART OPERATIONS =====

    public Cart getCart(Long userId) {
        String key = cartKey(userId);
        Cart cart = (Cart) redisTemplate.opsForValue().get(key);
        if (cart == null) {
            cart = Cart.builder().userId(userId).build();
        }
        return cart;
    }

    public Cart addItem(Long userId, AddItemRequest request) {
        Cart cart = getCart(userId);

        // Validate cart size
        if (cart.getItems().size() >= MAX_ITEMS_PER_CART &&
            cart.findItem(request.getProductId()).isEmpty()) {
            throw new ShopKartException.ValidationException(
                "Cart cannot have more than " + MAX_ITEMS_PER_CART + " items");
        }

        // Validate quantity
        int existingQty = cart.findItem(request.getProductId())
                .map(CartItem::getQuantity).orElse(0);
        int newQty = existingQty + request.getQuantity();

        if (newQty > MAX_QUANTITY_PER_ITEM) {
            throw new ShopKartException.ValidationException(
                "Maximum quantity per item is " + MAX_QUANTITY_PER_ITEM);
        }

        CartItem item = CartItem.builder()
                .productId(request.getProductId())
                .productName(request.getProductName())
                .sku(request.getSku())
                .price(request.getPrice())
                .mrp(request.getMrp())
                .quantity(request.getQuantity())
                .thumbnailUrl(request.getThumbnailUrl())
                .maxQuantityAllowed(MAX_QUANTITY_PER_ITEM)
                .addedAt(Instant.now())
                .build();

        cart.addOrUpdateItem(item);
        saveCart(userId, cart);

        log.debug("Added item {} to cart for user {}", request.getProductId(), userId);
        return cart;
    }

    public Cart updateItemQuantity(Long userId, Long productId, int quantity) {
        Cart cart = getCart(userId);

        CartItem item = cart.findItem(productId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("CartItem", productId));

        if (quantity <= 0) {
            cart.removeItem(productId);
        } else if (quantity > MAX_QUANTITY_PER_ITEM) {
            throw new ShopKartException.ValidationException(
                "Maximum quantity per item is " + MAX_QUANTITY_PER_ITEM);
        } else {
            item.setQuantity(quantity);
        }

        saveCart(userId, cart);
        return cart;
    }

    public Cart removeItem(Long userId, Long productId) {
        Cart cart = getCart(userId);
        if (!cart.removeItem(productId)) {
            throw new ShopKartException.ResourceNotFoundException("CartItem", productId);
        }
        saveCart(userId, cart);
        return cart;
    }

    public Cart applyCoupon(Long userId, String couponCode) {
        Cart cart = getCart(userId);
        if (cart.getItems().isEmpty()) {
            throw new ShopKartException.ValidationException("Cart is empty");
        }

        // TODO: Call coupon-service to validate coupon
        // For now, mock a 10% discount
        BigDecimal discount = cart.getTotalPrice().multiply(BigDecimal.valueOf(0.10));
        cart.setCouponCode(couponCode);
        cart.setCouponDiscount(discount);
        saveCart(userId, cart);

        log.info("Coupon {} applied to cart for user {}", couponCode, userId);
        return cart;
    }

    public Cart removeCoupon(Long userId) {
        Cart cart = getCart(userId);
        cart.setCouponCode(null);
        cart.setCouponDiscount(null);
        saveCart(userId, cart);
        return cart;
    }

    public void clearCart(Long userId) {
        Cart cart = getCart(userId);
        cart.clear();
        saveCart(userId, cart);
        log.info("Cart cleared for user {}", userId);
    }

    public CartSummary getCartSummary(Long userId) {
        Cart cart = getCart(userId);
        return CartSummary.builder()
                .userId(userId)
                .itemCount(cart.getTotalItems())
                .totalMrp(cart.getTotalMrp())
                .totalDiscount(cart.getTotalDiscount())
                .finalAmount(cart.getFinalAmount())
                .deliveryCharge(BigDecimal.ZERO) // Free delivery
                .couponCode(cart.getCouponCode())
                .couponDiscount(cart.getCouponDiscount())
                .build();
    }

    public void mergeGuestCart(Long userId, Long guestId) {
        Cart guestCart = getCart(guestId);
        if (guestCart.getItems().isEmpty()) return;

        Cart userCart = getCart(userId);
        for (CartItem item : guestCart.getItems()) {
            userCart.addOrUpdateItem(item);
        }

        saveCart(userId, userCart);
        clearCart(guestId);
        log.info("Merged guest cart {} into user cart {}", guestId, userId);
    }

    // ===== PRIVATE HELPERS =====

    private void saveCart(Long userId, Cart cart) {
        String key = cartKey(userId);
        redisTemplate.opsForValue().set(key, cart, CART_TTL_DAYS, TimeUnit.DAYS);
    }

    private String cartKey(Long userId) {
        return CART_KEY_PREFIX + userId;
    }

    // ===== INNER DTOs =====

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class AddItemRequest {
        private Long productId;
        private String productName;
        private String sku;
        private BigDecimal price;
        private BigDecimal mrp;
        private int quantity;
        private String thumbnailUrl;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class CartSummary {
        private Long userId;
        private int itemCount;
        private BigDecimal totalMrp;
        private BigDecimal totalDiscount;
        private BigDecimal deliveryCharge;
        private BigDecimal finalAmount;
        private String couponCode;
        private BigDecimal couponDiscount;
    }
}
