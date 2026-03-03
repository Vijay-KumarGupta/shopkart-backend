package com.shopkart.product.service;

import com.shopkart.common.events.DomainEvents;
import com.shopkart.common.exception.ShopKartException;
import com.shopkart.common.response.PagedResponse;
import com.shopkart.product.dto.ProductDtos.*;
import com.shopkart.product.entity.Inventory;
import com.shopkart.product.entity.Product;
import com.shopkart.product.repository.InventoryRepository;
import com.shopkart.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    @CacheEvict(value = "products", allEntries = true)
    public ProductResponse createProduct(CreateProductRequest request, Long sellerId) {
        // Generate unique SKU
        String sku = "SK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .sku(sku)
                .price(request.getPrice())
                .mrp(request.getMrp())
                .categoryId(request.getCategoryId())
                .sellerId(sellerId)
                .brand(request.getBrand())
                .imageUrls(request.getImageUrls())
                .status(Product.ProductStatus.ACTIVE)
                .build();

        Product saved = productRepository.save(product);

        // Create inventory record
        Inventory inventory = Inventory.builder()
                .product(saved)
                .availableQuantity(request.getInitialStock())
                .lowStockThreshold(request.getLowStockThreshold() != null ? request.getLowStockThreshold() : 10)
                .build();

        inventoryRepository.save(inventory);
        log.info("Product created: {} (id={}, sku={})", saved.getName(), saved.getId(), saved.getSku());

        return mapToProductResponse(saved, inventory);
    }

    @Cacheable(value = "product", key = "#productId")
    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("Product", productId));

        if (product.getStatus() == Product.ProductStatus.DELETED) {
            throw new ShopKartException.ResourceNotFoundException("Product", productId);
        }

        Inventory inventory = inventoryRepository.findByProductId(productId).orElse(null);
        return mapToProductResponse(product, inventory);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ProductSummaryResponse> searchProducts(
            String query, Long categoryId, String brand,
            Double minPrice, Double maxPrice,
            String sortBy, String sortDir,
            int page, int size) {

        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort sort = switch (sortBy != null ? sortBy : "relevance") {
            case "price" -> Sort.by(direction, "price");
            case "rating" -> Sort.by(direction, "averageRating");
            case "popularity" -> Sort.by(direction, "soldCount");
            case "newest" -> Sort.by(Sort.Direction.DESC, "createdAt");
            default -> Sort.by(Sort.Direction.DESC, "soldCount");
        };

        Pageable pageable = PageRequest.of(page, Math.min(size, 48), sort);

        Page<Product> products = productRepository.searchProducts(
                query, categoryId, brand,
                minPrice != null ? java.math.BigDecimal.valueOf(minPrice) : null,
                maxPrice != null ? java.math.BigDecimal.valueOf(maxPrice) : null,
                pageable);

        return PagedResponse.from(products.map(this::mapToProductSummaryResponse));
    }

    @Transactional(readOnly = true)
    public PagedResponse<ProductSummaryResponse> getProductsByCategory(Long categoryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "soldCount"));
        Page<Product> products = productRepository.findByCategoryIdAndStatus(
                categoryId, Product.ProductStatus.ACTIVE, pageable);
        return PagedResponse.from(products.map(this::mapToProductSummaryResponse));
    }

    @Transactional
    @CacheEvict(value = {"product", "products"}, key = "#productId")
    public ProductResponse updateProduct(Long productId, UpdateProductRequest request, Long sellerId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("Product", productId));

        if (!product.getSellerId().equals(sellerId)) {
            throw new ShopKartException.ForbiddenException("You don't own this product");
        }

        if (request.getName() != null) product.setName(request.getName());
        if (request.getDescription() != null) product.setDescription(request.getDescription());
        if (request.getPrice() != null) product.setPrice(request.getPrice());
        if (request.getMrp() != null) product.setMrp(request.getMrp());
        if (request.getBrand() != null) product.setBrand(request.getBrand());

        Product updated = productRepository.save(product);
        Inventory inventory = inventoryRepository.findByProductId(productId).orElse(null);

        return mapToProductResponse(updated, inventory);
    }

    // ===== INVENTORY MANAGEMENT =====

    @Transactional
    public boolean reserveStock(String orderId, List<StockReservationRequest.Item> items) {
        for (StockReservationRequest.Item item : items) {
            Inventory inventory = inventoryRepository.findByProductIdForUpdate(item.getProductId())
                    .orElseThrow(() -> new ShopKartException.ResourceNotFoundException("Inventory", item.getProductId()));

            if (!inventory.reserve(item.getQuantity())) {
                // Rollback all previous reservations
                log.warn("Insufficient stock for product {} in order {}. Requested: {}, Available: {}",
                        item.getProductId(), orderId, item.getQuantity(), inventory.getAvailableQuantity());

                // Publish failure event
                kafkaTemplate.send("shopkart.inventory.reserved", orderId,
                        DomainEvents.InventoryReservedEvent.builder()
                                .orderId(orderId)
                                .success(false)
                                .failureReason("Insufficient stock for product: " + item.getProductId())
                                .occurredAt(Instant.now())
                                .build());
                return false;
            }

            inventoryRepository.save(inventory);

            // Check low stock
            if (inventory.isLowStock()) {
                kafkaTemplate.send("shopkart.inventory.low", String.valueOf(item.getProductId()),
                        DomainEvents.InventoryLowEvent.builder()
                                .productId(item.getProductId())
                                .currentStock(inventory.getAvailableQuantity())
                                .threshold(inventory.getLowStockThreshold())
                                .occurredAt(Instant.now())
                                .build());
            }
        }

        // Publish success event
        kafkaTemplate.send("shopkart.inventory.reserved", orderId,
                DomainEvents.InventoryReservedEvent.builder()
                        .orderId(orderId)
                        .success(true)
                        .occurredAt(Instant.now())
                        .build());

        return true;
    }

    @Transactional
    public void releaseStock(List<StockReservationRequest.Item> items) {
        for (StockReservationRequest.Item item : items) {
            inventoryRepository.findByProductIdForUpdate(item.getProductId())
                    .ifPresent(inv -> {
                        inv.releaseReservation(item.getQuantity());
                        inventoryRepository.save(inv);
                    });
        }
    }

    @Transactional
    public void confirmStock(List<StockReservationRequest.Item> items) {
        for (StockReservationRequest.Item item : items) {
            inventoryRepository.findByProductIdForUpdate(item.getProductId())
                    .ifPresent(inv -> {
                        inv.confirmReservation(item.getQuantity());
                        inventoryRepository.save(inv);
                    });
        }
        // Increment sold counts
        items.forEach(item -> productRepository.incrementSoldCount(item.getProductId(), item.getQuantity()));
    }

    // ===== MAPPING =====

    private ProductResponse mapToProductResponse(Product p, Inventory inv) {
        return ProductResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .sku(p.getSku())
                .price(p.getPrice())
                .mrp(p.getMrp())
                .discountPercent(p.getDiscountPercent())
                .categoryId(p.getCategoryId())
                .sellerId(p.getSellerId())
                .brand(p.getBrand())
                .averageRating(p.getAverageRating())
                .reviewCount(p.getReviewCount())
                .soldCount(p.getSoldCount())
                .status(p.getStatus().name())
                .imageUrls(p.getImageUrls())
                .availableStock(inv != null ? inv.getAvailableQuantity() : 0)
                .inStock(inv != null && inv.isAvailable())
                .createdAt(p.getCreatedAt())
                .build();
    }

    private ProductSummaryResponse mapToProductSummaryResponse(Product p) {
        return ProductSummaryResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .price(p.getPrice())
                .mrp(p.getMrp())
                .discountPercent(p.getDiscountPercent())
                .brand(p.getBrand())
                .averageRating(p.getAverageRating())
                .reviewCount(p.getReviewCount())
                .thumbnailUrl(p.getImageUrls().isEmpty() ? null : p.getImageUrls().get(0))
                .inStock(p.getStatus() == Product.ProductStatus.ACTIVE)
                .build();
    }
}
