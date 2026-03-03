package com.shopkart.common.exception;

import org.springframework.http.HttpStatus;

public class ShopKartException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public ShopKartException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }

    // ---- Subclasses for each domain ----

    public static class ResourceNotFoundException extends ShopKartException {
        public ResourceNotFoundException(String resource, Object id) {
            super(resource + " not found with id: " + id, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        }
    }

    public static class ValidationException extends ShopKartException {
        public ValidationException(String message) {
            super(message, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
    }

    public static class UnauthorizedException extends ShopKartException {
        public UnauthorizedException(String message) {
            super(message, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    public static class ForbiddenException extends ShopKartException {
        public ForbiddenException(String message) {
            super(message, HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    public static class InsufficientStockException extends ShopKartException {
        public InsufficientStockException(Long productId, int requested, int available) {
            super("Insufficient stock for product " + productId + ". Requested: " + requested + ", Available: " + available,
                  HttpStatus.CONFLICT, "INSUFFICIENT_STOCK");
        }
    }

    public static class PaymentException extends ShopKartException {
        public PaymentException(String message) {
            super(message, HttpStatus.PAYMENT_REQUIRED, "PAYMENT_FAILED");
        }
    }

    public static class DuplicateResourceException extends ShopKartException {
        public DuplicateResourceException(String message) {
            super(message, HttpStatus.CONFLICT, "DUPLICATE_RESOURCE");
        }
    }
}
