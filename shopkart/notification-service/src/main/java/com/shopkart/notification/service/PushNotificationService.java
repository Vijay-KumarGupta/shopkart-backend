package com.shopkart.notification.service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class PushNotificationService {

    public void sendToUser(Long userId, PushRequest request) {
        log.info("[FCM] Push to user {}: {} — {}", userId, request.getTitle(), request.getBody());
        // In production:
        // 1. Fetch FCM tokens for userId from device token table
        // 2. Use Firebase Admin SDK to send multicast message
        // Example:
        //   MulticastMessage message = MulticastMessage.builder()
        //       .addAllTokens(tokens)
        //       .setNotification(Notification.builder().setTitle(title).setBody(body).build())
        //       .putAllData(data)
        //       .build();
        //   FirebaseMessaging.getInstance().sendEachForMulticast(message);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PushRequest {
        private String title;
        private String body;
        private String imageUrl;
        @Builder.Default
        private Map<String, String> data = new HashMap<>();

        public PushRequest data(String key, String value) {
            data.put(key, value);
            return this;
        }
    }
}
