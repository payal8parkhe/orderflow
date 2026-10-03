# OrderFlow – Distributed Microservices Order Processing & Fulfillment Platform

**OrderFlow** is a distributed microservices platform built with **Java 21**, **Spring Boot 3**, **PostgreSQL**, **Redis**, **Apache Kafka (KRaft mode)**, and **Docker**.

The system is decomposed into independent microservices operating on a database-per-service pattern, coordinated asynchronously through Kafka sagas and unified behind a reverse-proxy API Gateway hosting a single-page dashboard.

---

## Architecture Overview

```
                                  [ Browser / Client ]
                                           │
                                           │ HTTP Port 8080
                                           ▼
                       ┌───────────────────────────────────────────────┐
                       │             orderflow-api-gateway             │
                       │   • Reverse Proxy & Route Dispatcher          │
                       │   • Hash-Based SPA Frontend (No Modals)       │
                       │   • Live Telemetry Stream (Admin-Only)        │
                       │   • Resilience4j Circuit Breakers             │
                       │   • MDC Request Correlation Tracing           │
                       └───────┬───────────────┬───────────────┬───────┬───────┬───────┐
                               │               │               │       │       │
            ┌──────────────────┘               │               │       │       └──────────────────┐
            │ HTTP (8081)                      │ HTTP (8082)   │ HTTP (8083)   │ HTTP (8084)      │ HTTP (8085)
            ▼                                  ▼               ▼               ▼                  ▼
  ┌───────────────────┐              ┌───────────────────┐   ┌───────────────┐ ┌────────────────┐ ┌────────────────┐
  │   order-service   │              │  payment-service  │   │delivery-servic│ │notif.-service  │ │catalog-service │
  │ • Multi-Tenant    │              │ • Razorpay HMAC   │   │• Courier Fleet│ │• Live SSE Push │ │• 95%+ Read Cch │
  │   Seller Domain   │              │   Verification    │   │• Live Tracking│ │• Event Consumer│ │• Multi-SKU Cat.│
  │ • Verified Reviews│              │ • COD Settlement  │   │• Proof of     │ │• Order Alerts  │ │• Redis Tiered  │
  │ • Outbox Pattern  │              │ • Saga Coordinator│   │  Delivery     │ │• Read Tracking │ │• Event Producer│
  │ • Pessimistic Inv.│              │ • Refund Ledger   │   └───────┬───────┘ └───────┬────────┘ └───────┬────────┘
  │ • Auth & Orders   │              └─────────┬─────────┘           │                 │                  │
  └─────────┬─────────┘                        │                     │                 │                  │
            │                                  │                     │                 │                  │
            ▼                                  ▼                     ▼                 ▼                  ▼
    [orderflow_order_db]             [orderflow_payment_db] [orderflow_deliv.] [orderflow_notif]  [orderflow_cat_db]
            │                                  │                     │                 │                  │
            └──────────────────────────────────┼─────────────────────┴─────────────────┴──────────────────┘
                                               │
                                               ▼
                                 ┌───────────────────────────┐
                                 │     Apache Kafka KRaft    │
                                 │  • order-events           │
                                 │  • payment-events         │
                                 │  • delivery-events        │
                                 │  • catalog-events         │
                                 │  • cart-events            │
                                 └───────────────────────────┘
```

## Microservices Topology

| Service | Container Name | Port | Database | Responsibilities |
|---|---|---|---|---|
| **`api-gateway`** | `orderflow-api-gateway` | **8080** | Stateless | Unified entry point, reverse proxy routing with SSE stream pass-through, static SPA UI hosting, live secured Kafka event telemetry (`/api/events/recent`), Resilience4j circuit breakers, MDC `X-Correlation-ID` header management. |
| **`order-service`** | `orderflow-order-service` | **8081** | `orderflow_order_db` | User identity & JWT auth, multi-tenant seller portal (`ROLE_SELLER`), verified purchase reviews, Transactional Outbox pattern, pessimistic row write locks, 2-tier cart (Redis + Postgres), transactional order lifecycle. |
| **`payment-service`** | `orderflow-payment-service` | **8082** | `orderflow_payment_db` | Razorpay HMAC-SHA256 signature verification, sandbox payment simulation, Cash on Delivery (COD) settlement, automated refunds, idempotent Kafka event listeners. |
| **`delivery-service`** | `orderflow-delivery-service` | **8083** | `orderflow_delivery_db` | Courier assignments, tracking generation, doorstep delivery progression (`ASSIGNED` -> `PICKED_UP` -> `OUT_FOR_DELIVERY` -> `DELIVERED`). |
| **`notification-service`** | `orderflow-notification-service` | **8084** | `orderflow_notification_db` | Event-driven customer notification dispatch, Kafka event consumer (`order-events`, `payment-events`, `delivery-events`), live real-time Server-Sent Events (SSE) push streaming (`/api/notifications/stream`), read-state tracking. |
| **`catalog-service`** | `orderflow-catalog-service` | **8085** | `orderflow_catalog_db` | Dedicated read-heavy product catalog, multi-variant SKU attributes, Redis caching (`@Cacheable("products")`), and Kafka event choreography (`catalog-events`). |
| **`orderflow-common`** | N/A | Library | Shared domain models, enums (`Role.ROLE_SELLER`), product DTOs, exceptions, centralized JWT security (`JwtTokenProvider`, `JwtAuthenticationFilter`), and Kafka event DTOs. |

---

## Getting Started

### Prerequisites
- Java 21 JDK
- Docker & Docker Compose

### 1. Building the Microservices
The Maven wrapper (`mvnw`) builds all modules into individual runnable Spring Boot JARs:
```bash
./mvnw clean package -DskipTests
```

### 2. Running the Platform in Docker (All 4 Microservices)
Start PostgreSQL (with 3 separate service databases), Redis, Kafka (KRaft), and the 4 containerized microservices:
```bash
docker compose up --build -d
```
All 7 containers initialize on the internal `orderflow-network`. The interactive dashboard and unified APIs become accessible at `http://localhost:8080`.

To inspect running containers:
```bash
docker compose ps
```

### 3. Executing Enterprise End-to-End Verification
To execute the complete 20-stage automated E2E test suite across the container stack:
```bash
./verify_orderflow.sh
```

---

## Default Demo Accounts

The database is pre-seeded with ready-to-use accounts for each system role:

| Role | Email | Password | Access / Landing View |
|---|---|---|---|
| **Merchant / Seller** | `merchant@orderflow.io` | `MerchantPassword123!` | Merchant Hub (`#/seller/products`), Store Catalog & Order Lines |
| **Customer** | `customer@orderflow.io` | `CustomerPassword123!` | Storefront (`#/store`), Cart, Checkout, Live Orders |
| **Admin** | `admin@orderflow.io` | `AdminPassword123!` | Admin Portal (`#/admin`), Live Kafka Telemetry, Platform Controls |

The Single Page Application at `http://localhost:8080/#/auth` also provides one-click quick-fill buttons for each of these demo accounts.

---

OrderFlow includes an interactive Swagger UI dashboard allowing full endpoint exploration and direct testing:

- **Swagger UI Dashboard**: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
- **OpenAPI 3.0 JSON Specification**: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

### Testing Authenticated Endpoints in Swagger UI:
1. Open [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html).
2. Execute `POST /api/auth/login` (or `/api/auth/register`) and copy the returned `token`.
3. Click the green **Authorize 🔓** button at the top right of the Swagger UI page.
4. Paste the token into the value box and click **Authorize**.
5. All protected endpoints (such as `POST /api/orders` or `PATCH /api/orders/admin/{id}/status`) can now be tested directly in the browser!

---

## REST API Documentation

### Authentication (`/api/auth`)
| Method | Endpoint | Description | Auth Required |
|---|---|---|---|
| `POST` | `/api/auth/register` | Register customer or admin user | Public |
| `POST` | `/api/auth/register-seller` | Register vendor/seller and create merchant record | Public |
| `POST` | `/api/auth/login` | Authenticate and obtain JWT token | Public |

### Multi-Tenant Seller Portal (`/api/seller`)
| Method | Endpoint | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/seller/profile` | View authenticated merchant profile | `ROLE_SELLER` |
| `GET` | `/api/seller/products` | Retrieve all products in seller's store catalog | `ROLE_SELLER` |
| `POST` | `/api/seller/products` | Create new product assigned to seller | `ROLE_SELLER` |
| `PUT` | `/api/seller/products/{id}` | Update product (enforces ownership) | `ROLE_SELLER` |
| `DELETE` | `/api/seller/products/{id}` | Delete product and associated SKU inventory | `ROLE_SELLER` |
| `GET` | `/api/seller/orders` | View customer order line items for seller's products | `ROLE_SELLER` |

### Products & SKU Catalog (`/api/products`)
| Method | Endpoint | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/products` | Paginated product catalog | Public |
| `GET` | `/api/products/{id}` | Product details by ID | Public |
| `GET` | `/api/products/category/{category}` | Search products by category | Public |
| `GET` | `/api/products/{id}/variants` | Retrieve all SKU variants for product | Public |
| `POST` | `/api/products/{id}/variants` | Create new SKU variant | Admin / Seller |
| `POST` | `/api/products` | Create product and initial stock | Admin |

### Product Reviews & Ratings (`/api/reviews`)
| Method | Endpoint | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/reviews/product/{productId}` | List product reviews | Public |
| `GET` | `/api/reviews/product/{productId}/summary` | Rating summary & verified counts | Public |
| `POST` | `/api/reviews/product/{productId}` | Submit review (auto-detects verified purchase) | Customer |

### In-App Notification Center (`/api/notifications`)
| Method | Endpoint | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/notifications` | Customer notification alerts feed | Customer |
| `GET` | `/api/notifications/unread-count` | Unread notifications count | Customer |
| `PATCH` | `/api/notifications/{id}/read` | Mark individual notification as read | Customer |
| `PATCH` | `/api/notifications/read-all` | Mark all user notifications as read | Customer |

### Payment Gateway & Sagas (`/api/payments`)
| Method | Endpoint | Description | Auth Required |
|---|---|---|---|
| `POST` | `/api/payments` | Process payment simulation (or COD) | Customer |
| `POST` | `/api/payments/gateway/create-order` | Create official Razorpay gateway order | Customer |
| `POST` | `/api/payments/gateway/verify` | Verify Razorpay HMAC-SHA256 signature | Customer |
| `GET` | `/api/payments/order/{orderId}` | Check payment record status | Customer / Admin |

### Kafka Telemetry (`/api/events`)
| Method | Endpoint | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/events/recent` | Live Kafka KRaft event stream | **`ROLE_ADMIN` strictly** |

---

## Razorpay Gateway Adapter & Cryptographic Verification

OrderFlow implements an authentic cryptographic gateway adapter adhering to standard merchant integration protocols:

1. **Order Initiation (`POST /api/payments/gateway/create-order`)**:
   - Converts the order total into currency subunits (paise for INR: 1 INR = 100 paise).
   - Generates an official `order_...` identifier.

2. **Client-Side Signature Capture**:
   - The browser or checkout client interacts with the Razorpay modal / API and receives `razorpay_payment_id` and `razorpay_signature`.

3. **Cryptographic HMAC-SHA256 Verification (`POST /api/payments/gateway/verify`)**:
   - Backend computes:
     $$\text{HMAC-SHA256}(\text{order\_id} + "|" + \text{payment\_id}, \text{secret})$$
   - Compares the calculated signature with the received `razorpay_signature` using `MessageDigest.isEqual` to prevent timing attacks.
   - Rejects forged signatures with HTTP 400 Bad Request.
   - Upon valid signature, updates payment status to `COMPLETED` and publishes a `PAYMENT_COMPLETED` saga event to Kafka.

---

## 20-Stage End-to-End Automated Verification

Execute the test suite at any time:
```bash
chmod +x verify_orderflow.sh
./verify_orderflow.sh
```

The script runs sequentially through:
1. Actuator Health & MDC Request Correlation
2. Admin Authentication & Role Provisioning
3. Customer Self-Service Registration
4. Customer Address Book Management
5. Product Catalog & Batch Queries
6. Concurrency Stress Test (Pessimistic Row Write Locks)
7. 2-Tier Cart Merging (Guest -> Account)
8. Multi-Item Cart Checkout & Address Snapshotting
9. Payment Failure Compensating Saga Cancellation
10. Main Order Payment Completion
11. Delivery Fulfillment Choreographed Saga
12. Authentic Cash on Delivery (COD) Doorstep Settlement
13. Order Cancellation & Automated Kafka Payment Refund
14. Kafka Saga Event Stream Verification
15. Multi-SKU Variant Stock Isolation
16. Kafka Telemetry Security Lockdown (`ROLE_ADMIN` required)
17. Multi-Tenant Seller Registration & Catalog Isolation
18. Product Reviews & Verified Purchase Detection
19. In-App Notification Center & Transactional Alerts
20. Razorpay Gateway Adapter & HMAC-SHA256 Signature Verification

---

## Automated Test Suite

The platform includes automated testing spanning pure unit tests, multi-threaded concurrency verification, and end-to-end Kafka saga integration tests.

Run all tests via Maven:
```bash
./mvnw clean test
```

### Test Coverage Highlights:
- **`OrderServiceUnitTest`**: Pure Mockito tests verifying all prohibited state transitions (e.g. `COMPLETED -> CANCELLED`, `SHIPPED -> CONFIRMED`), idempotent event handling, and insufficient inventory rejections.
- **`InventoryPessimisticLockConcurrencyTest`**: Real JPA multi-threaded concurrency test (10 simultaneous threads competing for 5 inventory units). Asserts exactly 5 purchases succeed and 5 fail, with 0 oversold inventory under burst load.
- **`OrderSagaIntegrationTest`**: Embedded Kafka + in-memory JPA integration test walking the asynchronous saga:
  - `PAYMENT_COMPLETED` Kafka event consumption transitioning order status to `CONFIRMED`.
  - `PAYMENT_FAILED` compensating transaction triggering order cancellation and stock release.
  - Idempotency test asserting that duplicate Kafka events do not corrupt state.
- **`PaymentServiceTest`**: Validates payment security including remote order lookup verification, price-tampering rejections, duplicate payment prevention, and failure simulations.
- **`NotificationServiceTest`**: Validates event-driven alert dispatch, unread counters, user isolation on mark-as-read, and decoupled Kafka event consumption across `order-events`, `payment-events`, and `delivery-events`.

---

## Architecture Decision Records (ADRs)

Key architectural boundaries, trade-offs, and invariants are formally documented as Architecture Decision Records following the industry-standard "Problem Named → Decision Made → What It Cost" framework:

- [**ADR 0001: Microservice Decomposition and Boundary Mapping**](docs/adr/0001-microservice-decomposition-and-boundaries.md)
  - Maps OrderFlow against standard e-commerce/OMS reference architectures (Uber Eats, DoorDash, Shopify).
  - Rationalizes why Payment and Delivery are isolated, why Notifications are extracted asynchronously, and defines the extraction criteria for Catalog/Cart.
- [**ADR 0002: Inventory Co-Location and Pessimistic Locking Trade-off**](docs/adr/0002-inventory-co-location-consistency-tradeoff.md)
  - Evaluates transactional row locking vs. distributed reservation sagas (TTL holds, compensation timers).
  - Documents the rationale for keeping inventory inside `order-service` to guarantee zero overselling under high concurrency without distributed saga overhead.

---

## Architecture Trade-offs & Production Considerations

### 1. Transactional Outbox Pattern vs. Dual-Write
- **Problem**: Writing to PostgreSQL and directly publishing to Kafka in the same application method creates the dual-write problem — if the database commits but the Kafka broker is unreachable or network times out, data becomes permanently inconsistent.
- **Implementation**: The `order-service` implements the **Transactional Outbox Pattern**. State changes and the corresponding event payloads are saved to the `outbox_events` table within the same local ACID transaction. A Spring `@TransactionalEventListener(phase = AFTER_COMMIT)` triggers asynchronous dispatch to Kafka, with a background scheduled poller acting as a fallback for any stranded records.

### 2. Kafka Error Handling & Dead Letter Topics (DLT)
- **Problem**: Poison pill messages or transient downstream failures can block Kafka consumer partitions indefinitely if listeners crash in unhandled loops.
- **Implementation**: All services configure Spring Kafka's `DefaultErrorHandler` with exponential backoff (1s initial interval, 2.0x multiplier, max 3 retries). Records that exceed retry thresholds are automatically published to dedicated Dead Letter Topics (`order-events.DLT`, `payment-events.DLT`, `delivery-events.DLT`) via `DeadLetterPublishingRecoverer`, preserving partition consumption and providing an audit trail for manual inspection.

### 3. Concurrency Control: Pessimistic vs. Optimistic Locking
- **Trade-off**: Optimistic locking (`@Version`) works well under low contention. However, during flash sales or high-volume concurrent purchases on the same SKU, optimistic locking leads to high transaction abort rates and retry storms.
- **Decision**: Inventory reservation utilizes pessimistic write locking (`SELECT ... FOR UPDATE` via `findByProductIdWithLock`), serializing updates at the database row level. This guarantees zero overselling without application-level retry overhead, at the cost of requiring short database transaction durations to minimize lock contention.

### 4. API Gateway Resilience & Circuit Breaking
- **Implementation**: The API Gateway integrates **Resilience4j** circuit breaking and HTTP request timeouts (3 seconds) in its reverse proxy filter. Downstream service outages or latency spikes are isolated, preventing thread starvation and cascading failures across the gateway.

### 5. Database Migrations with Flyway
- **Implementation**: Replaced Hibernate's automatic schema modifications (`ddl-auto: update`) with version-controlled **Flyway** migrations (`V1__init_...schema.sql`) and `ddl-auto: validate`. This ensures predictable, reproducible schema changes across environments and eliminates accidental production schema corruption.

### 6. Authentication & Identity: Symmetric JWT vs. OAuth2 / JWKS
- **Current Approach**: Stateless HMAC-SHA256 JWT tokens verified via shared secret across services, minimizing latency and eliminating remote authorization server round-trips.
- **Production Evolution**: For enterprise scale with independently managed security boundaries, migrating to asymmetric key pairs (RSA/ECDSA) with a centralized OpenID Connect / OAuth2 server exposing a JSON Web Key Set (`/.well-known/jwks.json`) and Redis-backed token revocation lists would be the recommended next phase.
