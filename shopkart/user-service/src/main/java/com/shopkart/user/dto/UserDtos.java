package com.shopkart.user.dto;

import com.shopkart.user.entity.User;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.Instant;

public final class UserDtos {

    private UserDtos() {}

    // ===== REQUESTS =====

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 100)
        private String name;

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @Pattern(regexp = "^[6-9]\\d{9}$", message = "Invalid Indian phone number")
        private String phone;

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).*$",
                 message = "Password must contain uppercase, lowercase, and digit")
        private String password;
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        private String emailOrPhone;

        @NotBlank
        private String password;

        private String deviceId;
        private String ipAddress;
    }

    @Data
    public static class UpdateProfileRequest {
        @Size(min = 2, max = 100)
        private String name;

        @Pattern(regexp = "^[6-9]\\d{9}$", message = "Invalid Indian phone number")
        private String phone;

        private String profileImageUrl;
    }

    @Data
    public static class ChangePasswordRequest {
        @NotBlank
        private String currentPassword;

        @NotBlank
        @Size(min = 8)
        @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).*$")
        private String newPassword;
    }

    @Data
    public static class AddressRequest {
        @NotBlank private String fullName;
        @NotBlank @Pattern(regexp = "^[6-9]\\d{9}$") private String phone;
        @NotBlank private String addressLine1;
        private String addressLine2;
        @NotBlank private String city;
        @NotBlank private String state;
        @NotBlank @Pattern(regexp = "^[1-9][0-9]{5}$", message = "Invalid pincode") private String pincode;
        @NotBlank private String country;
        private boolean isDefault;
        private String type;
    }

    // ===== RESPONSES =====

    @Data @Builder
    public static class UserResponse {
        private Long id;
        private String name;
        private String email;
        private String phone;
        private String role;
        private String status;
        private String profileImageUrl;
        private boolean emailVerified;
        private boolean phoneVerified;
        private Instant createdAt;
        private Instant lastLoginAt;
    }

    @Data @Builder
    public static class AuthResponse {
        private String accessToken;
        private String refreshToken;
        private String tokenType;
        private long expiresIn;
        private UserResponse user;
    }

    @Data @Builder
    public static class AddressResponse {
        private Long id;
        private String fullName;
        private String phone;
        private String addressLine1;
        private String addressLine2;
        private String city;
        private String state;
        private String pincode;
        private String country;
        private boolean isDefault;
        private String type;
    }
}
