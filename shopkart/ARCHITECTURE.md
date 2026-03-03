# ShopKart Backend — Architecture & Scale Design
## Built for 100 Million Customers

---

## System Architecture

```
                          ┌─────────────────────────────────┐
                          │         CDN (CloudFront)         │
                          │   Static assets, image caching   │
                          └──────────────┬──────────────────┘
                                         │
                          ┌──────────────▼──────────────────┐
                          │     Load Balancer (AWS ALB)      │
                          │     SSL termination, WAF         │
                          └──────────────┬──────────────────┘
                                         │
                          ┌──────────────▼──────────────────┐
                          │    API Gateway (4-30 pods)       │
                          │  JWT Auth · Rate Limiting        │
                          │  Request Routing · Observability │
                          └──┬──────┬──────┬──────┬─────────┘
                             │      │      │      │
              ┌──────────────▼─┐  ┌─▼──┐ ┌▼───┐ ┌▼──────────────┐
              │  User Service  │  │Cart│ │Prod│ │ Order Service │
              │  (6-50 pods)   │  │Svc │ │Svc │ │  (4-30 pods)  │
              └────────┬───────┘  └──┬─┘ └──┬─┘ └──────┬────────┘
                       │             │       │           │
              ┌────────▼──┐   ┌──────▼──┐   │   ┌──────▼───────┐
              │ PostgreSQL │   │  Redis  │   │   │ PostgreSQL   │
              │  (Sharded) │   │ Cluster │   │   │  (Sharded)   │
              └────────────┘   └─────────┘   │   └──────────────┘
                                           ┌─▼──────────────────┐
                                           │  Elasticsearch     │
                                           │  (Search Index)    │
                                           └────────────────────┘

                    ┌──────────────────────────────────────┐
                    │           Apache Kafka               │
                    │   Event Streaming · Saga Orchestration│
                    └──────┬──────────┬──────────┬─────────┘
                           │          │          │
                   ┌───────▼──┐ ┌─────▼──┐ ┌────▼──────────┐
                   │ Payment  │ │Notif.  │ │ Analytics     │
                   │ Service  │ │Service │ │ Service       │
                   └──────────┘ └────────┘ └───────────────┘
```

---

## Microservices

| Service | Purpose | Stack | Scale |
|---------|---------|-------|-------|
| **API Gateway** | Routing, auth, rate limiting | Spring Cloud Gateway | 4–30 pods |
| **User Service** | Auth, profiles, JWT | Spring Boot + PostgreSQL + Redis | 6–50 pods |
| **Product Service** | Catalog, search, inventory | Spring Boot + PostgreSQL + Elasticsearch | 8–100 pods |
| **Cart Service** | Shopping cart | Spring Boot + Redis | 5–60 pods |
| **Order Service** | Order lifecycle, Saga | Spring Boot + PostgreSQL + Kafka | 4–30 pods |
| **Payment Service** | Payment processing | Spring Boot + PostgreSQL + Razorpay | 3–20 pods |
| **Notification Service** | Email/SMS/Push | Spring Boot + Kafka + SES/FCM | 2–10 pods |

---

## Scale Strategies for 100M Users

### Database Layer
- **Horizontal sharding** — Users partitioned by ID ranges (25M per shard)
- **Orders** — Partitioned by `placed_at` date (monthly partitions, archived yearly)
- **Products** — Partitioned by status (active/inactive split)
- **Read replicas** — 3 replicas per primary for read-heavy product queries
- **Connection pooling** — HikariCP with 50 max connections per service pod

### Caching
- **Redis Cluster** — 6 nodes (3 primary + 3 replica), 48 GB total memory
- Cart data: Stored entirely in Redis (30-day TTL)
- Product data: Redis with 10-minute TTL, 60-second TTL on gateway
- User sessions: Redis with JWT blacklisting
- Rate limiting: Redis sliding window counters

### Search
- **Elasticsearch** — 3-node cluster for full-text product search
- Product writes indexed async via Kafka consumer
- Supports: fuzzy search, filters, faceting, sorting

### Event Streaming (Kafka)
- **Order Saga**: OrderPlaced → InventoryReserved → PaymentCompleted → OrderConfirmed
- **Compensation**: PaymentFailed → OrderCancelled → InventoryReleased → RefundInitiated
- Topic partitions scaled to traffic: 12 partitions for hot paths
- Dead Letter Queues (DLQ) for retry logic

### API Rate Limiting
- Per-IP: 10 req/second (gateway), 100 req/minute (auth endpoints)
- Per-User: 1000 req/minute for authenticated users
- Per-Seller: 5000 req/minute for product management

---

## Security

- **JWT** — 15-minute access tokens, 7-day refresh tokens
- **Password hashing** — BCrypt with strength 12
- **Account lockout** — 5 failed attempts → 30-minute lockout
- **Rate limiting** — IP-based sliding window via Redis
- **Payment verification** — HMAC-SHA256 signature validation (Razorpay)
- **SQL injection prevention** — JPA parameterized queries only
- **XSS/CSRF** — Spring Security defaults + custom filters

---

## Observability

- **Metrics** — Micrometer + Prometheus + Grafana dashboards
- **Tracing** — Zipkin distributed tracing across services
- **Logging** — Structured JSON logs → ELK Stack (Elasticsearch + Logstash + Kibana)
- **Alerts** — PagerDuty integration for P0/P1 incidents
- **Health** — Spring Actuator `/health/readiness` + `/health/liveness` for K8s

---

## Local Development

```bash
# Start everything
docker-compose up -d

# Service URLs
API Gateway:    http://localhost:8000
Eureka:         http://localhost:8761
Kafka UI:       http://localhost:8080
Grafana:        http://localhost:3000 (admin/admin123)
Prometheus:     http://localhost:9090
Elasticsearch:  http://localhost:9200

# Build all services
mvn clean package -DskipTests

# Run a single service
cd user-service && mvn spring-boot:run
```

---

## API Endpoints Summary

### Auth (`/api/v1/auth`)
```
POST /register          Register new user
POST /login             Login (returns JWT)
POST /refresh           Refresh access token
POST /logout            Blacklist refresh token
```

### Users (`/api/v1/users`)
```
GET  /me                Get profile
PUT  /me                Update profile
PUT  /me/password       Change password
GET  /me/addresses      List addresses
POST /me/addresses      Add address
DELETE /me/addresses/:id Delete address
```

### Products (`/api/v1/products`)
```
GET  /                  Search products (query, filters, sort, page)
GET  /:id               Get product details
POST /                  Create product (seller)
PUT  /:id               Update product (seller)
GET  /category/:id      Products by category
```

### Cart (`/api/v1/cart`)
```
GET  /                  Get cart
POST /items             Add item
PUT  /items/:productId  Update quantity
DELETE /items/:productId Remove item
POST /coupon            Apply coupon
DELETE /coupon          Remove coupon
DELETE /                Clear cart
```

### Orders (`/api/v1/orders`)
```
POST /                  Place order
GET  /                  List user orders (paginated)
GET  /:id               Get order details
POST /:id/cancel        Cancel order
```

### Payments (`/api/v1/payments`)
```
POST /initiate          Initiate payment
POST /verify            Verify payment (after gateway callback)
GET  /order/:orderId    Get payment by order
```
