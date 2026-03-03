package com.shopkart.notification.service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class SmsService {

    @Value("${notification.sms.provider:twilio}")
    private String provider;

    @Value("${notification.sms.enabled:true}")
    private boolean enabled;

    public void send(String phoneNumber, String message) {
        if (!enabled) {
            log.debug("[MOCK SMS] To: {} | Message: {}", phoneNumber, message);
            return;
        }

        try {
            // In production: inject Twilio or AWS SNS client
            // Message.creator(new PhoneNumber(phoneNumber), new PhoneNumber(FROM_NUMBER), message).create();
            log.info("[{}] SMS sent to {}: {}", provider, phoneNumber, message.substring(0, Math.min(50, message.length())));
        } catch (Exception e) {
            log.error("Failed to send SMS to {}: {}", phoneNumber, e.getMessage());
        }
    }

    public void sendOtp(String phoneNumber, String otp) {
        send(phoneNumber, "Your ShopKart OTP is: " + otp + ". Valid for 10 minutes. Do not share this OTP.");
    }
}
