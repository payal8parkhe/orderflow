# ADR 0001: Microservice Decomposition and Boundary Mapping

## Status
Accepted

## Context & Problem
In e-commerce and order management systems (OMS), a common architectural anti-pattern is decomposing systems naively by nouns (e.g., separate microservice for every entity) rather than by architectural drivers. This leads to distributed monoliths, high latency from synchronous RPC cascades, distributed transactions, and severe operational overhead.

The industry standard reference pattern for e-commerce and OMS (converged upon by Uber Eats, DoorDash, Shopify, and enterprise OMS architectures) defines service extraction around three core principles:
1. **Different Consistency Requirements**: Transactional ACID core vs. eventual consistency vs. ephemeral data.
2. **Different Scaling Curves**: Read-heavy cacheable workloads vs. write-intensive hot-spots vs. async background execution.
3. **Different Compliance & Trust Boundaries**: PCI-DSS payment compliance boundaries, external actor roles (e.g., couriers/warehouse), and third-party dependency blast radiuses.

## Standard Reference Decomposition vs. OrderFlow

| Boundary | Architectural Driver | Reference Standard | OrderFlow Architecture |
|---|---|---|---|
| **Payment** | PCI-DSS audit boundary, 3rd-party gateway failure domain | Separate service (extracted early) | `payment-service` (Port 8082) ✅ |
| **Fulfillment / Delivery** | Distinct actor (courier), long-lived lifecycle outlasting checkout | Separate service (extracted mid) | `delivery-service` (Port 8083) ✅ |
| **Notification** | Fire-and-forget, async event consumer, real-time push | Separate service (extracted early) | `notification-service` (Port 8084) ✅ (with live SSE push stream) |
| **Catalog / Product** | Read-heavy (95%+), cacheable, independent scaling curve | Separate service (extracted early) | `catalog-service` (Port 8085) ✅ (Redis cached `@Cacheable`) |
| **Inventory** | Atomic reservation, race condition hotspot | Mid (kept with order until contention demands distributed reservation) | Co-located in `order-service` with `PESSIMISTIC_WRITE` (ADR 0002) ⚠️ |
| **Order Core** | Transactional source of truth for "what was bought" | Central transactional service | `order-service` (Port 8081) ✅ |
| **Cart** | High write churn, ephemeral session data | Separate service (extracted mid) | Co-located in `order-service` (Roadmap candidate) ⚠️ |

## Problem → Decision → Cost Framing

### 1. Payment Service (`payment-service`)
- **Problem**: Card transactions touch external payment gateways (Razorpay, Stripe) with variable latency (500ms–3s) and external failure modes. Additionally, PCI-DSS compliance audits require minimizing the surface area of cardholder data.
- **Decision**: Isolate payment processing into a dedicated `payment-service`. A payment gateway outage or latency spike degrades only checkout settlement, never product browsing or order history, and confines PCI audit scope to a single deployable unit.
- **Cost**: No database-level foreign key between `payments` and `orders`. State synchronization must occur via an asynchronous Kafka saga (`PaymentSagaCoordinator`), introducing eventual consistency.

### 2. Delivery Service (`delivery-service`)
- **Problem**: Delivery workflows are operated by a completely different actor (courier logistics and dispatchers, not shoppers). The delivery lifecycle is long-lived (hours to days) and outlives the sub-second checkout transaction.
- **Decision**: Separate `delivery-service` with its own database and state machine (`PENDING` → `ASSIGNED` → `PICKED_UP` → `IN_TRANSIT` → `DELIVERED`). Courier scaling, GPS telemetry, or courier-app bugs cannot impact the checkout path.
- **Cost**: Requires asynchronous coordination over `order-events` and `delivery-events`. Shopper views delivery status through eventual consistency.

### 3. Notification Service (`notification-service`)
- **Problem**: Customer notifications (order confirmations, payment receipts, shipment updates) are fire-and-forget operations involving external delivery channels and real-time client push. Running notification dispatch synchronously inside transactional order processing couples order commits to external mail server latencies and retries.
- **Decision**: Extract `notification-service` as an independent consumer listening to `order-events`, `payment-events`, and `delivery-events`. Added real-time Server-Sent Events (SSE) push streaming (`GET /api/notifications/stream`) to immediately broadcast notifications to active browser clients without polling delays.
- **Cost**: Additional service deployable and Kafka consumer group to monitor; client SSE connection lifecycle management and keep-alive heartbeats.

### 4. Inventory Boundary (Kept in `order-service`)
- **Problem**: Reserving stock and creating the order must be strictly atomic. Splitting inventory prematurely forces a distributed two-phase reservation saga (reserve → timeout → release) which introduces oversell vulnerabilities and inventory leaks under high concurrency.
- **Decision**: Retain inventory management inside `order-service` using PostgreSQL row-level pessimistic locking (`PESSIMISTIC_WRITE`) within the order creation transaction.
- **Cost**: See [ADR 0002](0002-inventory-co-location-consistency-tradeoff.md) for full trade-off analysis.

### 5. Catalog Service (`catalog-service`)
- **Problem**: Catalog browsing is 95%+ read-heavy, highly cacheable, and experiences different traffic spikes (marketing campaigns, SEO crawlers) than transactional order creation. Bundling it inside `order-service` forces `order-service` to absorb read load spikes.
- **Decision**: Extracted into a dedicated `catalog-service` (Port 8085) with independent database (`orderflow_catalog_db`), multi-tier Redis caching (`@Cacheable("products")`), and asynchronous Kafka event publishing (`catalog-events`) for catalog mutations.
- **Cost**: Requires separate database provisioning and cache invalidation choreography (`@CacheEvict`) on writes.

## Consequences & Operational Impact
- Each extracted service (`order-service`, `payment-service`, `delivery-service`, `notification-service`, `catalog-service`) maintains its own PostgreSQL database, Flyway migrations, and independent schema.
- API Gateway routes `/api/products/**` to `catalog-service` (port 8085) and `/api/notifications/**` to `notification-service` (port 8084).
- Real-time client push notifications stream seamlessly via reverse proxy SSE chunked streaming.
- The Transactional Outbox pattern guarantees that database state changes and Kafka events are published atomically without dual-write hazards.
