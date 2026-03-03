package com.shopkart.notification.service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Email service abstraction.
 * Switch between AWS SES, SendGrid, or Mailgun via config.
 */
@Slf4j
@Service
public class EmailService {

    @Value("${notification.email.provider:ses}")
    private String provider;

    @Value("${notification.email.from:noreply@shopkart.com}")
    private String fromAddress;

    @Value("${notification.email.enabled:true}")
    private boolean enabled;

    public void send(EmailRequest request) {
        if (!enabled) {
            log.debug("[MOCK EMAIL] To: {} | Subject: {}", request.getTo(), request.getSubject());
            return;
        }

        try {
            String htmlContent = buildHtmlContent(request.getTemplateId(), request.getVariables());
            sendViaProvider(request.getTo(), request.getSubject(), htmlContent);
            log.info("Email sent to {} via {}", request.getTo(), provider);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", request.getTo(), e.getMessage());
            // In production: push to retry queue
        }
    }

    private void sendViaProvider(String to, String subject, String content) {
        // In production, inject the SES/SendGrid SDK and call the API
        // Example for AWS SES:
        //   sesClient.sendEmail(SendEmailRequest.builder()
        //       .destination(d -> d.toAddresses(to))
        //       .message(m -> m.subject(c -> c.data(subject)).body(b -> b.html(c -> c.data(content))))
        //       .source(fromAddress).build());
        log.info("[{}] Sending email to {} — '{}'", provider, to, subject);
    }

    private String buildHtmlContent(String templateId, Map<String, String> variables) {
        // In production: use Thymeleaf or Freemarker templates from classpath
        return switch (templateId) {
            case "welcome" ->
                "<h1>Welcome, " + variables.getOrDefault("name", "Customer") + "!</h1>" +
                "<p>Thanks for joining ShopKart. Start exploring 150M+ products!</p>";
            case "order_confirmed" ->
                "<h2>Order Confirmed ✅</h2>" +
                "<p>Order ID: <strong>" + variables.getOrDefault("orderId", "") + "</strong></p>" +
                "<p>Total: " + variables.getOrDefault("amount", "") + "</p>";
            case "order_shipped" ->
                "<h2>Your Order is Shipped 🚚</h2>" +
                "<p>Tracking: " + variables.getOrDefault("trackingNumber", "") + "</p>" +
                "<p>Courier: " + variables.getOrDefault("courier", "") + "</p>";
            case "low_stock_alert" ->
                "<h2>⚠️ Low Stock Alert</h2>" +
                "<p>Product: " + variables.getOrDefault("productName", "") + " (" +
                variables.getOrDefault("productId", "") + ")</p>" +
                "<p>Current: " + variables.getOrDefault("currentStock", "") + " units</p>";
            default -> "<p>" + templateId + "</p>";
        };
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmailRequest {
        private String to;
        private String subject;
        private String templateId;
        @Builder.Default
        private Map<String, String> variables = new HashMap<>();

        public EmailRequest variable(String key, String value) {
            variables.put(key, value);
            return this;
        }
    }
}
