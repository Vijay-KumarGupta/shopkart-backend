# ShopKart — Java Microservices Backend
## Designed for 100M+ Customers

```
shopkart/
├── api-gateway/          # Spring Cloud Gateway — rate limiting, routing, auth
├── user-service/         # Auth, profiles, addresses (JWT + OAuth2)
├── product-service/      # Catalog, search, inventory (Elasticsearch)
├── cart-service/         # Redis-backed cart (sub-ms latency)
├── order-service/        # Order lifecycle, Saga pattern
├── payment-service/      # Payment processing, refunds
├── notification-service/ # Email/SMS/Push via Kafka consumers
└── common/               # Shared DTOs, exceptions, utils
```

## Tech Stack
- Java 21 + Spring Boot 3.2
- Spring Cloud Gateway + Eureka Service Discovery
- PostgreSQL (sharded) + Redis Cluster + Elasticsearch
- Apache Kafka (event streaming)
- JWT + Spring Security
- Docker + Kubernetes ready
