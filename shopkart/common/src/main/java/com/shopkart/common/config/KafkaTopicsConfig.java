package com.shopkart.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic Configuration
 * Topics are auto-created but explicit creation ensures correct partition counts.
 *
 * For 100M users / 5M daily orders:
 * - High-traffic topics (order.placed, product views): 12 partitions
 * - Medium traffic (user events, payments): 6 partitions
 * - Low traffic (notifications, alerts): 3 partitions
 */
@Configuration
public class KafkaTopicsConfig {

    // ===== USER TOPICS =====
    @Bean public NewTopic userRegisteredTopic() {
        return TopicBuilder.name("shopkart.user.registered")
                .partitions(6).replicas(3).build();
    }

    @Bean public NewTopic userLoginTopic() {
        return TopicBuilder.name("shopkart.user.login")
                .partitions(6).replicas(3).build();
    }

    // ===== ORDER TOPICS =====
    @Bean public NewTopic orderPlacedTopic() {
        return TopicBuilder.name("shopkart.order.placed")
                .partitions(12)          // Highest traffic
                .replicas(3)
                .build();
    }

    @Bean public NewTopic orderConfirmedTopic() {
        return TopicBuilder.name("shopkart.order.confirmed")
                .partitions(12).replicas(3).build();
    }

    @Bean public NewTopic orderShippedTopic() {
        return TopicBuilder.name("shopkart.order.shipped")
                .partitions(6).replicas(3).build();
    }

    @Bean public NewTopic orderDeliveredTopic() {
        return TopicBuilder.name("shopkart.order.delivered")
                .partitions(6).replicas(3).build();
    }

    @Bean public NewTopic orderCancelledTopic() {
        return TopicBuilder.name("shopkart.order.cancelled")
                .partitions(6).replicas(3).build();
    }

    // ===== PAYMENT TOPICS =====
    @Bean public NewTopic paymentRequestTopic() {
        return TopicBuilder.name("shopkart.payment.request")
                .partitions(12).replicas(3).build();
    }

    @Bean public NewTopic paymentCompletedTopic() {
        return TopicBuilder.name("shopkart.payment.completed")
                .partitions(12).replicas(3).build();
    }

    @Bean public NewTopic paymentFailedTopic() {
        return TopicBuilder.name("shopkart.payment.failed")
                .partitions(6).replicas(3).build();
    }

    @Bean public NewTopic paymentRefundedTopic() {
        return TopicBuilder.name("shopkart.payment.refunded")
                .partitions(6).replicas(3).build();
    }

    // ===== INVENTORY TOPICS =====
    @Bean public NewTopic inventoryReservedTopic() {
        return TopicBuilder.name("shopkart.inventory.reserved")
                .partitions(12).replicas(3).build();
    }

    @Bean public NewTopic inventoryLowTopic() {
        return TopicBuilder.name("shopkart.inventory.low")
                .partitions(3).replicas(3).build();
    }

    // ===== DEAD LETTER TOPICS (for retries) =====
    @Bean public NewTopic orderPlacedDltTopic() {
        return TopicBuilder.name("shopkart.order.placed.DLT")
                .partitions(3).replicas(3).build();
    }

    @Bean public NewTopic paymentRequestDltTopic() {
        return TopicBuilder.name("shopkart.payment.request.DLT")
                .partitions(3).replicas(3).build();
    }
}
