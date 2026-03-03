package com.shopkart.user.service;

import com.shopkart.common.events.DomainEvents;
import com.shopkart.common.exception.ShopKartException;
import com.shopkart.user.dto.UserDtos.*;
import com.shopkart.user.entity.Address;
import com.shopkart.user.entity.User;
import com.shopkart.user.repository.AddressRepository;
import com.shopkart.user.repository.UserRepository;
import com.shopkart.user.security.JwtTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String REFRESH_TOKEN_PREFIX = "refresh:";
    private static final String RATE_LIMIT_PREFIX = "rate:login:";

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check for existing user
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ShopKartException.DuplicateResourceException(
                "Email already registered: " + request.getEmail());
        }
        if (request.getPhone() != null && userRepository.existsByPhone(request.getPhone())) {
            throw new ShopKartException.DuplicateResourceException(
                "Phone already registered: " + request.getPhone());
        }

        // Create user
        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail().toLowerCase())
                .phone(request.getPhone())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(User.UserRole.CUSTOMER)
                .status(User.UserStatus.ACTIVE)
                .build();

        User saved = userRepository.save(user);
        log.info("New user registered: {} (id={})", saved.getEmail(), saved.getId());

        // Publish event to Kafka
        kafkaTemplate.send("shopkart.user.registered", String.valueOf(saved.getId()),
                DomainEvents.UserRegisteredEvent.builder()
                        .userId(saved.getId())
                        .email(saved.getEmail())
                        .name(saved.getName())
                        .phone(saved.getPhone())
                        .registeredAt(Instant.now())
                        .build());

        // Generate tokens
        return buildAuthResponse(saved);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Rate limiting: max 10 attempts per IP per minute
        String rateLimitKey = RATE_LIMIT_PREFIX + request.getIpAddress();
        Long attempts = redisTemplate.opsForValue().increment(rateLimitKey);
        if (attempts == 1) {
            redisTemplate.expire(rateLimitKey, 60, TimeUnit.SECONDS);
        }
        if (attempts > 10) {
            throw new ShopKartException.ValidationException("Too many login attempts. Please try again in 1 minute.");
        }

        // Find user
        User user = userRepository.findByEmailOrPhone(request.getEmailOrPhone())
                .orElseThrow(() -> new ShopKartException.UnauthorizedException("Invalid credentials"));

        // Check status
        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new ShopKartException.UnauthorizedException("Account is " + user.getStatus().name().toLowerCase());
        }

        // Check lockout
        if (user.isLocked()) {
            throw new ShopKartException.UnauthorizedException("Account is temporarily locked due to multiple failed attempts");
        }

        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            user.incrementFailedAttempts();
            userRepository.save(user);
            throw new ShopKartException.UnauthorizedException("Invalid credentials");
        }

        // Successful login
        user.resetFailedAttempts();
        userRepository.updateLastLogin(user.getId(), Instant.now());

        // Publish login event
        kafkaTemplate.send("shopkart.user.login", String.valueOf(user.getId()),
                DomainEvents.UserLoginEvent.builder()
                        .userId(user.getId())
                        .email(user.getEmail())
                        .ipAddress(request.getIpAddress())
                        .loginAt(Instant.now())
                        .build());

        log.info("User logged in: {} (id={})", user.getEmail(), user.getId());
        return buildAuthResponse(user);
    }

    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtTokenService.validateToken(refreshToken)) {
            throw new ShopKartException.UnauthorizedException("Invalid refresh token");
        }

        if (!"REFRESH".equals(jwtTokenService.extractTokenType(refreshToken))) {
            throw new ShopKartException.UnauthorizedException("Token is not a refresh token");
        }

        // Check if token is blacklisted
        String tokenKey = REFRESH_TOKEN_PREFIX + refreshToken;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(tokenKey + ":blacklist"))) {
            throw new ShopKartException.UnauthorizedException("Refresh token has been revoked");
        }

        Long userId = jwtTokenService.extractUserId(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("User", userId));

        return buildAuthResponse(user);
    }

    public void logout(String refreshToken, Long userId) {
        // Blacklist the refresh token
        String blacklistKey = REFRESH_TOKEN_PREFIX + refreshToken + ":blacklist";
        redisTemplate.opsForValue().set(blacklistKey, "1", 7, TimeUnit.DAYS);
        log.info("User logged out: id={}", userId);
    }

    @Transactional(readOnly = true)
    public UserResponse getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("User", userId));
        return mapToUserResponse(user);
    }

    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("User", userId));

        if (request.getName() != null) user.setName(request.getName());
        if (request.getPhone() != null) {
            if (userRepository.existsByPhone(request.getPhone())) {
                throw new ShopKartException.DuplicateResourceException("Phone number already in use");
            }
            user.setPhone(request.getPhone());
        }
        if (request.getProfileImageUrl() != null) user.setProfileImageUrl(request.getProfileImageUrl());

        User updated = userRepository.save(user);
        log.info("User profile updated: id={}", userId);
        return mapToUserResponse(updated);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("User", userId));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new ShopKartException.ValidationException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Password changed for user: id={}", userId);
    }

    // ===== ADDRESS MANAGEMENT =====

    @Transactional
    public AddressResponse addAddress(Long userId, AddressRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("User", userId));

        if (user.getAddresses().size() >= 10) {
            throw new ShopKartException.ValidationException("Maximum 10 addresses allowed per user");
        }

        // If new address is default, unset existing default
        if (request.isDefault()) {
            addressRepository.unsetDefaultForUser(userId);
        }

        Address address = Address.builder()
                .user(user)
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .addressLine1(request.getAddressLine1())
                .addressLine2(request.getAddressLine2())
                .city(request.getCity())
                .state(request.getState())
                .pincode(request.getPincode())
                .country(request.getCountry())
                .isDefault(request.isDefault())
                .type(Address.AddressType.valueOf(request.getType() != null ? request.getType() : "HOME"))
                .build();

        Address saved = addressRepository.save(address);
        return mapToAddressResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> getAddresses(Long userId) {
        return addressRepository.findByUserIdOrderByIsDefaultDesc(userId)
                .stream()
                .map(this::mapToAddressResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteAddress(Long userId, Long addressId) {
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("Address", addressId));
        if (!address.getUser().getId().equals(userId)) {
            throw new ShopKartException.ForbiddenException("Access denied");
        }
        addressRepository.delete(address);
    }

    // ===== PRIVATE HELPERS =====

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtTokenService.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = jwtTokenService.generateRefreshToken(user.getId(), user.getEmail());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenService.getAccessTokenExpiry() / 1000)
                .user(mapToUserResponse(user))
                .build();
    }

    private UserResponse mapToUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole().name())
                .status(user.getStatus().name())
                .profileImageUrl(user.getProfileImageUrl())
                .emailVerified(user.isEmailVerified())
                .phoneVerified(user.isPhoneVerified())
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .build();
    }

    private AddressResponse mapToAddressResponse(Address a) {
        return AddressResponse.builder()
                .id(a.getId())
                .fullName(a.getFullName())
                .phone(a.getPhone())
                .addressLine1(a.getAddressLine1())
                .addressLine2(a.getAddressLine2())
                .city(a.getCity())
                .state(a.getState())
                .pincode(a.getPincode())
                .country(a.getCountry())
                .isDefault(a.isDefault())
                .type(a.getType().name())
                .build();
    }
}
