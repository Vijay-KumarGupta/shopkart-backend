package com.shopkart.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder) {
        return builder.routes()

                // ===== USER SERVICE =====
                .route("user-service-auth", r -> r
                        .path("/api/v1/auth/**")
                        .filters(f -> f
                                .addRequestHeader("X-Service", "user-service")
                                .requestRateLimiter(config -> config
                                        .setRateLimiter(redisRateLimiter())
                                        .setKeyResolver(ipKeyResolver())))
                        .uri("lb://user-service"))

                .route("user-service-users", r -> r
                        .path("/api/v1/users/**")
                        .filters(f -> f
                                .filter(jwtAuthFilter())
                                .addRequestHeader("X-Service", "user-service"))
                        .uri("lb://user-service"))

                // ===== PRODUCT SERVICE =====
                .route("product-service-public", r -> r
                        .path("/api/v1/products/**")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f
                                .addResponseHeader("Cache-Control", "public, max-age=60")
                                .addRequestHeader("X-Service", "product-service"))
                        .uri("lb://product-service"))

                .route("product-service-private", r -> r
                        .path("/api/v1/products/**")
                        .filters(f -> f
                                .filter(jwtAuthFilter())
                                .addRequestHeader("X-Service", "product-service"))
                        .uri("lb://product-service"))

                .route("category-service", r -> r
                        .path("/api/v1/categories/**")
                        .uri("lb://product-service"))

                // ===== CART SERVICE =====
                .route("cart-service", r -> r
                        .path("/api/v1/cart/**")
                        .filters(f -> f
                                .filter(jwtAuthFilter()))
                        .uri("lb://cart-service"))

                // ===== ORDER SERVICE =====
                .route("order-service", r -> r
                        .path("/api/v1/orders/**")
                        .filters(f -> f
                                .filter(jwtAuthFilter()))
                        .uri("lb://order-service"))

                // ===== PAYMENT SERVICE =====
                .route("payment-service", r -> r
                        .path("/api/v1/payments/**")
                        .filters(f -> f
                                .filter(jwtAuthFilter()))
                        .uri("lb://payment-service"))

                .route("payment-webhook", r -> r
                        .path("/webhooks/payment/**")
                        .uri("lb://payment-service"))

                .build();
    }

    // These beans are defined in separate filter classes
    private org.springframework.cloud.gateway.filter.GatewayFilter jwtAuthFilter() {
        return (exchange, chain) -> chain.filter(exchange); // Placeholder
    }

    private org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter redisRateLimiter() {
        return new org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter(10, 20, 1);
    }

    private org.springframework.cloud.gateway.filter.ratelimit.KeyResolver ipKeyResolver() {
        return exchange -> reactor.core.publisher.Mono.justOrEmpty(
                exchange.getRequest().getRemoteAddress())
                .map(addr -> addr.getAddress().getHostAddress())
                .defaultIfEmpty("unknown");
    }
}
