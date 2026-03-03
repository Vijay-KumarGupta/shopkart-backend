package com.shopkart.order.service;

import com.shopkart.common.events.DomainEvents;
import com.shopkart.common.exception.ShopKartException;
import com.shopkart.common.response.PagedResponse;
import com.shopkart.order.dto.OrderDtos.*;
import com.shopkart.order.entity.Order;
import com.shopkart.order.entity.OrderItem;
import com.shopkart.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String ORDER_ID_PREFIX = "ORD-";

    /**
     * Step 1 of Saga: Create order in PENDING state, then request inventory reservation.
     */
    @Transactional
    public OrderResponse placeOrder(Long userId, PlaceOrderRequest request) {
        // Generate order ID
        String orderId = ORDER_ID_PREFIX + System.currentTimeMillis() + "-" +
                         UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        // Build order items
        List<OrderItem> items = request.getItems().stream()
                .map(i -> OrderItem.builder()
                        .productId(i.getProductId())
                        .productName(i.getProductName())
                        .sku(i.getSku())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .mrp(i.getMrp())
                        .thumbnailUrl(i.getThumbnailUrl())
                        .build())
                .collect(Collectors.toList());

        Order.ShippingAddress address = Order.ShippingAddress.builder()
                .fullName(request.getShippingAddress().getFullName())
                .phone(request.getShippingAddress().getPhone())
                .addressLine1(request.getShippingAddress().getAddressLine1())
                .addressLine2(request.getShippingAddress().getAddressLine2())
                .city(request.getShippingAddress().getCity())
                .state(request.getShippingAddress().getState())
                .pincode(request.getShippingAddress().getPincode())
                .country(request.getShippingAddress().getCountry())
                .build();

        Order order = Order.builder()
                .id(orderId)
                .userId(userId)
                .items(items)
                .totalMrp(request.getTotalMrp())
                .totalDiscount(request.getTotalDiscount())
                .deliveryCharge(request.getDeliveryCharge())
                .totalAmount(request.getTotalAmount())
                .shippingAddress(address)
                .paymentMethod(Order.PaymentMethod.valueOf(request.getPaymentMethod()))
                .couponCode(request.getCouponCode())
                .couponDiscount(request.getCouponDiscount())
                .status(Order.OrderStatus.PENDING)
                .build();

        items.forEach(i -> i.setOrder(order));
        Order saved = orderRepository.save(order);

        // Publish OrderPlacedEvent → triggers inventory reservation saga
        kafkaTemplate.send("shopkart.order.placed", orderId,
                DomainEvents.OrderPlacedEvent.builder()
                        .orderId(orderId)
                        .userId(userId)
                        .items(items.stream().map(i ->
                                DomainEvents.OrderItemEvent.builder()
                                        .productId(i.getProductId())
                                        .productName(i.getProductName())
                                        .quantity(i.getQuantity())
                                        .price(i.getUnitPrice())
                                        .build())
                                .collect(Collectors.toList()))
                        .totalAmount(request.getTotalAmount())
                        .paymentMethod(request.getPaymentMethod())
                        .placedAt(Instant.now())
                        .build());

        log.info("Order placed: {} for user {}", orderId, userId);
        return mapToOrderResponse(saved);
    }

    /**
     * Step 3 of Saga: Inventory confirmed → trigger payment.
     */
    @KafkaListener(topics = "shopkart.inventory.reserved", groupId = "order-service")
    @Transactional
    public void onInventoryReserved(DomainEvents.InventoryReservedEvent event) {
        if (!event.isSuccess()) {
            // Compensating transaction: cancel order
            orderRepository.findById(event.getOrderId()).ifPresent(order -> {
                order.cancel("Insufficient stock");
                orderRepository.save(order);
                log.warn("Order {} cancelled due to insufficient stock", event.getOrderId());
            });
            return;
        }

        Order order = orderRepository.findById(event.getOrderId()).orElse(null);
        if (order == null) return;

        // Publish payment request
        kafkaTemplate.send("shopkart.payment.request", event.getOrderId(),
                PaymentRequest.builder()
                        .orderId(event.getOrderId())
                        .userId(order.getUserId())
                        .amount(order.getTotalAmount())
                        .paymentMethod(order.getPaymentMethod().name())
                        .build());

        log.info("Inventory reserved for order {}, requesting payment", event.getOrderId());
    }

    /**
     * Step 5 of Saga: Payment completed → confirm order.
     */
    @KafkaListener(topics = "shopkart.payment.completed", groupId = "order-service")
    @Transactional
    public void onPaymentCompleted(DomainEvents.PaymentCompletedEvent event) {
        orderRepository.findById(event.getOrderId()).ifPresent(order -> {
            order.confirm();
            order.setPaymentId(event.getPaymentId());
            orderRepository.save(order);

            kafkaTemplate.send("shopkart.order.confirmed", event.getOrderId(),
                    DomainEvents.OrderConfirmedEvent.builder()
                            .orderId(event.getOrderId())
                            .userId(order.getUserId())
                            .totalAmount(order.getTotalAmount())
                            .confirmedAt(Instant.now())
                            .build());

            log.info("Order {} confirmed after payment {}", event.getOrderId(), event.getPaymentId());
        });
    }

    /**
     * Compensating transaction: payment failed → cancel order and release stock.
     */
    @KafkaListener(topics = "shopkart.payment.failed", groupId = "order-service")
    @Transactional
    public void onPaymentFailed(DomainEvents.PaymentFailedEvent event) {
        orderRepository.findById(event.getOrderId()).ifPresent(order -> {
            order.cancel("Payment failed: " + event.getFailureReason());
            order.setPaymentStatus(Order.PaymentStatus.FAILED);
            orderRepository.save(order);

            // Publish event to release inventory
            kafkaTemplate.send("shopkart.order.cancelled", event.getOrderId(),
                    DomainEvents.OrderCancelledEvent.builder()
                            .orderId(event.getOrderId())
                            .userId(order.getUserId())
                            .reason("Payment failed")
                            .cancelledAt(Instant.now())
                            .build());

            log.warn("Order {} cancelled due to payment failure", event.getOrderId());
        });
    }

    @Transactional
    public OrderResponse shipOrder(String orderId, ShipOrderRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("Order", orderId));

        order.ship(request.getTrackingNumber(), request.getCourierPartner(),
                   Instant.now().plus(request.getEstimatedDays(), ChronoUnit.DAYS));
        Order updated = orderRepository.save(order);

        kafkaTemplate.send("shopkart.order.shipped", orderId,
                DomainEvents.OrderShippedEvent.builder()
                        .orderId(orderId)
                        .userId(order.getUserId())
                        .trackingNumber(request.getTrackingNumber())
                        .courierPartner(request.getCourierPartner())
                        .shippedAt(Instant.now())
                        .estimatedDelivery(updated.getEstimatedDelivery())
                        .build());

        return mapToOrderResponse(updated);
    }

    @Transactional
    public OrderResponse cancelOrder(Long userId, String orderId, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("Order", orderId));

        if (!order.getUserId().equals(userId)) {
            throw new ShopKartException.ForbiddenException("Access denied");
        }

        if (order.getStatus() == Order.OrderStatus.SHIPPED ||
            order.getStatus() == Order.OrderStatus.DELIVERED) {
            throw new ShopKartException.ValidationException("Cannot cancel order after shipping");
        }

        order.cancel(reason);
        Order updated = orderRepository.save(order);

        // Release inventory + refund if paid
        kafkaTemplate.send("shopkart.order.cancelled", orderId,
                DomainEvents.OrderCancelledEvent.builder()
                        .orderId(orderId)
                        .userId(userId)
                        .reason(reason)
                        .cancelledAt(Instant.now())
                        .build());

        return mapToOrderResponse(updated);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long userId, String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("Order", orderId));

        if (!order.getUserId().equals(userId)) {
            throw new ShopKartException.ForbiddenException("Access denied");
        }

        return mapToOrderResponse(order);
    }

    @Transactional(readOnly = true)
    public PagedResponse<OrderSummaryResponse> getUserOrders(Long userId, int page, int size) {
        Page<Order> orders = orderRepository.findByUserIdOrderByPlacedAtDesc(
                userId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "placedAt")));
        return PagedResponse.from(orders.map(this::mapToOrderSummaryResponse));
    }

    // ===== MAPPING =====

    private OrderResponse mapToOrderResponse(Order o) {
        return OrderResponse.builder()
                .id(o.getId())
                .userId(o.getUserId())
                .items(o.getItems().stream().map(i ->
                        OrderResponse.ItemResponse.builder()
                                .productId(i.getProductId())
                                .productName(i.getProductName())
                                .quantity(i.getQuantity())
                                .unitPrice(i.getUnitPrice())
                                .mrp(i.getMrp())
                                .thumbnailUrl(i.getThumbnailUrl())
                                .build())
                        .collect(Collectors.toList()))
                .totalMrp(o.getTotalMrp())
                .totalDiscount(o.getTotalDiscount())
                .deliveryCharge(o.getDeliveryCharge())
                .totalAmount(o.getTotalAmount())
                .shippingAddress(o.getShippingAddress())
                .status(o.getStatus().name())
                .paymentMethod(o.getPaymentMethod() != null ? o.getPaymentMethod().name() : null)
                .paymentStatus(o.getPaymentStatus().name())
                .trackingNumber(o.getTrackingNumber())
                .courierPartner(o.getCourierPartner())
                .couponCode(o.getCouponCode())
                .couponDiscount(o.getCouponDiscount())
                .placedAt(o.getPlacedAt())
                .confirmedAt(o.getConfirmedAt())
                .shippedAt(o.getShippedAt())
                .deliveredAt(o.getDeliveredAt())
                .estimatedDelivery(o.getEstimatedDelivery())
                .build();
    }

    private OrderSummaryResponse mapToOrderSummaryResponse(Order o) {
        return OrderSummaryResponse.builder()
                .id(o.getId())
                .status(o.getStatus().name())
                .totalAmount(o.getTotalAmount())
                .itemCount(o.getItems().size())
                .placedAt(o.getPlacedAt())
                .estimatedDelivery(o.getEstimatedDelivery())
                .thumbnailUrl(o.getItems().isEmpty() ? null : o.getItems().get(0).getThumbnailUrl())
                .firstItemName(o.getItems().isEmpty() ? null : o.getItems().get(0).getProductName())
                .build();
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    static class PaymentRequest {
        private String orderId;
        private Long userId;
        private java.math.BigDecimal amount;
        private String paymentMethod;
    }
}
