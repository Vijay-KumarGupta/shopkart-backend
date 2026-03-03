-- ============================================================
-- ShopKart Database Initialization
-- Supports 100M+ users via table partitioning & sharding hints
-- ============================================================

-- Create databases
CREATE DATABASE shopkart_users;
CREATE DATABASE shopkart_products;
CREATE DATABASE shopkart_orders;
CREATE DATABASE shopkart_payments;

-- ===== USERS DB =====
\connect shopkart_users;

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(100) NOT NULL UNIQUE,
    phone         VARCHAR(15) UNIQUE,
    password_hash TEXT NOT NULL,
    role          VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    profile_image_url TEXT,
    email_verified BOOLEAN DEFAULT false,
    phone_verified BOOLEAN DEFAULT false,
    failed_login_attempts INT DEFAULT 0,
    locked_until  TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (id);

-- Partition by ranges for horizontal scaling
CREATE TABLE users_1_to_25m  PARTITION OF users FOR VALUES FROM (1) TO (25000000);
CREATE TABLE users_25m_to_50m PARTITION OF users FOR VALUES FROM (25000000) TO (50000000);
CREATE TABLE users_50m_to_75m PARTITION OF users FOR VALUES FROM (50000000) TO (75000000);
CREATE TABLE users_75m_to_100m PARTITION OF users FOR VALUES FROM (75000000) TO (100000000);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_phone ON users(phone);
CREATE INDEX idx_users_status ON users(status);
CREATE INDEX idx_users_created ON users(created_at);

CREATE TABLE addresses (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    full_name     VARCHAR(100) NOT NULL,
    phone         VARCHAR(15) NOT NULL,
    address_line1 TEXT NOT NULL,
    address_line2 TEXT,
    city          VARCHAR(100) NOT NULL,
    state         VARCHAR(100) NOT NULL,
    pincode       VARCHAR(10) NOT NULL,
    country       VARCHAR(50) NOT NULL DEFAULT 'India',
    is_default    BOOLEAN DEFAULT false,
    type          VARCHAR(20) DEFAULT 'HOME',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_addresses_user_id ON addresses(user_id);

-- ===== PRODUCTS DB =====
\connect shopkart_products;

CREATE TABLE categories (
    id        BIGSERIAL PRIMARY KEY,
    name      VARCHAR(200) NOT NULL,
    parent_id BIGINT REFERENCES categories(id),
    slug      VARCHAR(200) UNIQUE,
    image_url TEXT,
    is_active BOOLEAN DEFAULT true,
    sort_order INT DEFAULT 0
);

CREATE TABLE products (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(500) NOT NULL,
    description     TEXT NOT NULL,
    sku             VARCHAR(50) NOT NULL UNIQUE,
    price           NUMERIC(12,2) NOT NULL,
    mrp             NUMERIC(12,2) NOT NULL,
    category_id     BIGINT REFERENCES categories(id),
    seller_id       BIGINT NOT NULL,
    brand           VARCHAR(200),
    average_rating  DECIMAL(3,2) DEFAULT 0.00,
    review_count    INT DEFAULT 0,
    sold_count      INT DEFAULT 0,
    status          VARCHAR(30) DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
) PARTITION BY LIST (status);

CREATE TABLE products_active    PARTITION OF products FOR VALUES IN ('ACTIVE');
CREATE TABLE products_inactive  PARTITION OF products FOR VALUES IN ('INACTIVE', 'OUT_OF_STOCK', 'DELETED');

CREATE INDEX idx_products_category ON products(category_id);
CREATE INDEX idx_products_seller   ON products(seller_id);
CREATE INDEX idx_products_brand    ON products(brand);
CREATE INDEX idx_products_price    ON products(price);
CREATE INDEX idx_products_rating   ON products(average_rating DESC);
CREATE INDEX idx_products_sold     ON products(sold_count DESC);

-- Full-text search index
CREATE INDEX idx_products_fts ON products USING gin(to_tsvector('english', name || ' ' || COALESCE(brand, '')));

CREATE TABLE inventory (
    id                  BIGSERIAL PRIMARY KEY,
    product_id          BIGINT NOT NULL UNIQUE REFERENCES products(id),
    available_quantity  INT NOT NULL DEFAULT 0,
    reserved_quantity   INT NOT NULL DEFAULT 0,
    low_stock_threshold INT NOT NULL DEFAULT 10,
    version             BIGINT DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE product_images (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    image_url  TEXT NOT NULL,
    sort_order INT DEFAULT 0
);

-- ===== ORDERS DB =====
\connect shopkart_orders;

-- Partition orders by year-month for archiving
CREATE TABLE orders (
    id                   VARCHAR(50) PRIMARY KEY,
    user_id              BIGINT NOT NULL,
    total_mrp            NUMERIC(12,2) NOT NULL,
    total_discount       NUMERIC(12,2) NOT NULL DEFAULT 0,
    delivery_charge      NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_amount         NUMERIC(12,2) NOT NULL,
    shipping_full_name   VARCHAR(100),
    shipping_phone       VARCHAR(15),
    shipping_address1    TEXT,
    shipping_address2    TEXT,
    shipping_city        VARCHAR(100),
    shipping_state       VARCHAR(100),
    shipping_pincode     VARCHAR(10),
    shipping_country     VARCHAR(50),
    status               VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    payment_method       VARCHAR(30),
    payment_status       VARCHAR(30) DEFAULT 'PENDING',
    payment_id           VARCHAR(50),
    tracking_number      VARCHAR(100),
    courier_partner      VARCHAR(100),
    coupon_code          VARCHAR(50),
    coupon_discount      NUMERIC(12,2),
    cancellation_reason  TEXT,
    cancelled_at         TIMESTAMPTZ,
    placed_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    confirmed_at         TIMESTAMPTZ,
    shipped_at           TIMESTAMPTZ,
    delivered_at         TIMESTAMPTZ,
    estimated_delivery   TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version              BIGINT DEFAULT 0
) PARTITION BY RANGE (placed_at);

-- Monthly partitions (create yearly via pg_partman in prod)
CREATE TABLE orders_2024 PARTITION OF orders FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE orders_2025 PARTITION OF orders FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE orders_2026 PARTITION OF orders FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

CREATE INDEX idx_orders_user_id  ON orders(user_id);
CREATE INDEX idx_orders_status   ON orders(status);
CREATE INDEX idx_orders_placed   ON orders(placed_at DESC);

CREATE TABLE order_items (
    id            BIGSERIAL PRIMARY KEY,
    order_id      VARCHAR(50) NOT NULL REFERENCES orders(id),
    product_id    BIGINT NOT NULL,
    product_name  VARCHAR(500) NOT NULL,
    sku           VARCHAR(50),
    quantity      INT NOT NULL,
    unit_price    NUMERIC(12,2) NOT NULL,
    mrp           NUMERIC(12,2) NOT NULL,
    thumbnail_url TEXT
);
CREATE INDEX idx_order_items_order_id   ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);

-- ===== PAYMENTS DB =====
\connect shopkart_payments;

CREATE TABLE payments (
    id                   VARCHAR(50) PRIMARY KEY,
    order_id             VARCHAR(50) NOT NULL UNIQUE,
    user_id              BIGINT NOT NULL,
    amount               NUMERIC(12,2) NOT NULL,
    currency             VARCHAR(5) DEFAULT 'INR',
    method               VARCHAR(30) NOT NULL,
    status               VARCHAR(30) NOT NULL DEFAULT 'INITIATED',
    gateway_order_id     VARCHAR(100),
    gateway_payment_id   VARCHAR(100),
    failure_reason       TEXT,
    paid_at              TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE INDEX idx_payments_user_id  ON payments(user_id);
CREATE INDEX idx_payments_status   ON payments(status);

CREATE TABLE refunds (
    id           VARCHAR(50) PRIMARY KEY,
    payment_id   VARCHAR(50) NOT NULL REFERENCES payments(id),
    amount       NUMERIC(12,2) NOT NULL,
    reason       TEXT,
    status       VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    processed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_refunds_payment_id ON refunds(payment_id);
