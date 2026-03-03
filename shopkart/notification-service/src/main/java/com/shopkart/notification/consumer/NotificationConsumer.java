package com.shopkart.notification.consumer;

import com.shopkart.common.events.DomainEvents;
import com.shopkart.notification.service.EmailService;
import com.shopkart.notification.service.SmsService;
import com.shopkart.notification.service.PushNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes all domain events and sends appropriate notifications.
 * Fully decoupled from business logic via Kafka.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final EmailService emailService;
    private final SmsService smsService;
    private final PushNotificationService pushService;

    @KafkaListener(topics = "shopkart.user.registered", groupId = "notification-service")
    public void onUserRegistered(DomainEvents.UserRegisteredEvent event) {
        log.info("Sending welcome notification to user: {}", event.getEmail());

        emailService.send(EmailService.EmailRequest.builder()
                .to(event.getEmail())
                .subject("Welcome to ShopKart! 🎉")
                .templateId("welcome")
                .variable("name", event.getName())
                .build());

        if (event.getPhone() != null) {
            smsService.send(event.getPhone(),
                "Welcome to ShopKart! Your account has been created successfully. Happy Shopping! 🛒");
        }
    }

    @KafkaListener(topics = "shopkart.order.confirmed", groupId = "notification-service")
    public void onOrderConfirmed(DomainEvents.OrderConfirmedEvent event) {
        log.info("Sending order confirmation for order: {}", event.getOrderId());

        emailService.send(EmailService.EmailRequest.builder()
                .to(event.getEmail())
                .subject("Order Confirmed ✅ — " + event.getOrderId())
                .templateId("order_confirmed")
                .variable("orderId", event.getOrderId())
                .variable("amount", "₹" + event.getTotalAmount())
                .build());

        pushService.sendToUser(event.getUserId(),
                PushNotificationService.PushRequest.builder()
                        .title("Order Confirmed! 🎉")
                        .body("Your order " + event.getOrderId() + " has been confirmed")
                        .data("orderId", event.getOrderId())
                        .data("type", "ORDER_CONFIRMED")
                        .build());
    }

    @KafkaListener(topics = "shopkart.order.shipped", groupId = "notification-service")
    public void onOrderShipped(DomainEvents.OrderShippedEvent event) {
        log.info("Sending shipping notification for order: {}", event.getOrderId());

        emailService.send(EmailService.EmailRequest.builder()
                .to(event.getEmail())
                .subject("Your order is on its way! 🚚 — " + event.getOrderId())
                .templateId("order_shipped")
                .variable("orderId", event.getOrderId())
                .variable("trackingNumber", event.getTrackingNumber())
                .variable("courier", event.getCourierPartner())
                .variable("estimatedDelivery", event.getEstimatedDelivery().toString())
                .build());

        pushService.sendToUser(event.getUserId(),
                PushNotificationService.PushRequest.builder()
                        .title("Order Shipped! 📦")
                        .body("Order " + event.getOrderId() + " is out for delivery via " + event.getCourierPartner())
                        .data("orderId", event.getOrderId())
                        .data("trackingNumber", event.getTrackingNumber())
                        .data("type", "ORDER_SHIPPED")
                        .build());
    }

    @KafkaListener(topics = "shopkart.order.delivered", groupId = "notification-service")
    public void onOrderDelivered(DomainEvents.OrderDeliveredEvent event) {
        log.info("Sending delivery notification for order: {}", event.getOrderId());

        pushService.sendToUser(event.getUserId(),
                PushNotificationService.PushRequest.builder()
                        .title("Order Delivered! 🎁")
                        .body("Your order has been delivered. Please rate your experience.")
                        .data("orderId", event.getOrderId())
                        .data("type", "ORDER_DELIVERED")
                        .build());
    }

    @KafkaListener(topics = "shopkart.order.cancelled", groupId = "notification-service")
    public void onOrderCancelled(DomainEvents.OrderCancelledEvent event) {
        log.info("Sending cancellation notification for order: {}", event.getOrderId());

        pushService.sendToUser(event.getUserId(),
                PushNotificationService.PushRequest.builder()
                        .title("Order Cancelled")
                        .body("Order " + event.getOrderId() + " has been cancelled. Refund initiated if applicable.")
                        .data("orderId", event.getOrderId())
                        .data("type", "ORDER_CANCELLED")
                        .build());
    }

    @KafkaListener(topics = "shopkart.payment.refunded", groupId = "notification-service")
    public void onPaymentRefunded(DomainEvents.PaymentRefundedEvent event) {
        log.info("Sending refund notification for order: {}", event.getOrderId());

        pushService.sendToUser(event.getUserId(),
                PushNotificationService.PushRequest.builder()
                        .title("Refund Processed 💰")
                        .body("₹" + event.getRefundAmount() + " refund for order " + event.getOrderId() +
                              " will reflect in 3-5 business days")
                        .data("orderId", event.getOrderId())
                        .data("type", "PAYMENT_REFUNDED")
                        .build());
    }

    @KafkaListener(topics = "shopkart.inventory.low", groupId = "notification-service")
    public void onInventoryLow(DomainEvents.InventoryLowEvent event) {
        // Internal alert to seller/ops team
        log.warn("LOW STOCK ALERT: Product {} — {} units remaining", event.getProductId(), event.getCurrentStock());

        emailService.send(EmailService.EmailRequest.builder()
                .to("ops@shopkart.com")
                .subject("⚠️ Low Stock Alert: " + event.getProductName())
                .templateId("low_stock_alert")
                .variable("productId", String.valueOf(event.getProductId()))
                .variable("productName", event.getProductName())
                .variable("currentStock", String.valueOf(event.getCurrentStock()))
                .variable("threshold", String.valueOf(event.getThreshold()))
                .build());
    }
}
