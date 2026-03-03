package com.shopkart.product.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products",
       indexes = {
           @Index(name = "idx_product_category", columnList = "category_id"),
           @Index(name = "idx_product_seller", columnList = "seller_id"),
           @Index(name = "idx_product_sku", columnList = "sku", unique = true),
           @Index(name = "idx_product_status", columnList = "status")
       })
@Getter @Setter
@Builder @NoArgsConstructor @AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, unique = true, length = 50)
    private String sku;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal mrp;

    @Column(nullable = false)
    private Long categoryId;

    @Column(nullable = false)
    private Long sellerId;

    private String brand;

    @Builder.Default
    private double averageRating = 0.0;

    @Builder.Default
    private int reviewCount = 0;

    @Builder.Default
    private int soldCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProductStatus status = ProductStatus.ACTIVE;

    @OneToOne(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Inventory inventory;

    @ElementCollection
    @CollectionTable(name = "product_images", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "image_url")
    @Builder.Default
    private List<String> imageUrls = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ProductAttribute> attributes = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public BigDecimal getDiscountPercent() {
        if (mrp.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return mrp.subtract(price).multiply(BigDecimal.valueOf(100)).divide(mrp, 0, java.math.RoundingMode.HALF_UP);
    }

    public enum ProductStatus { ACTIVE, INACTIVE, OUT_OF_STOCK, DELETED }
}
