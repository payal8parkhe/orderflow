# ADR 0002: Inventory Co-Location and Pessimistic Locking Trade-off

## Status
Accepted

## Context & Problem
In e-commerce systems, inventory management is the single highest-contention point in the entire architecture. During high-concurrency events (e.g., flash sales, limited product drops), hundreds or thousands of shoppers attempt to purchase the same scarce SKU simultaneously.

There are two primary architectural patterns to handle inventory:
1. **Co-located Transactional Inventory (ACID)**: Keep product stock within the same relational database as the order transaction, using row-level pessimistic locking (`SELECT ... FOR UPDATE`).
2. **Extracted Inventory Microservice (Distributed Saga / TTL Reservation)**: Extract inventory into a separate service exposing a reservation API (`POST /reservations` with TTL, `POST /confirm`, `POST /release`).

Extracting inventory into its own microservice prematurely is one of the most common mistakes in distributed systems. It replaces a strict ACID guarantee with a distributed saga, introducing significant failure modes:
- **Phantom reservations**: Leased stock held by abandoned checkout sessions.
- **Race conditions**: Network timeouts during reservation confirmation leaving stock in ambiguous states.
- **Reconciliation overhead**: Need for distributed timers, dead-letter queues, and background reconciliation jobs to clean up leaked locks.

## Decision
We intentionally keep inventory co-located inside `order-service` and protect stock decrements using database row-level pessimistic locking (`LockModeType.PESSIMISTIC_WRITE`) within the `@Transactional` boundary of order creation.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :id")
Optional<Product> findByIdWithPessimisticLock(@Param("id") Long id);
```

### Why this decision is defensible:
1. **Strict Zero-Oversell Guarantee**: Stock decrement and order creation occur within a single database transaction. If stock is insufficient, the transaction rolls back immediately. Overselling is physically impossible under standard PostgreSQL ACID isolation.
2. **Empirically Proven**: Verified via `InventoryPessimisticLockConcurrencyTest`, where 10 concurrent threads attempt to purchase 5 remaining units of a SKU; exactly 5 succeed, 5 are rejected with out-of-stock, and 0 units are oversold.
3. **No Dual-Write or Distributed Rollback Required**: There is no risk of an order being created while inventory reservation fails, or of stock being decremented without an order record.

## Costs & Trade-offs
- **Coupled Scaling**: `order-service` and inventory share database CPU and I/O. Write throughput on hot SKUs is bounded by PostgreSQL row-lock serialization.
- **Lock Contention**: Highly popular SKUs queue transactions at the database row level. Database connection pool sizing must accommodate row-lock wait times.

## Trigger for Future Extraction
Inventory should only be extracted into a standalone service when:
1. Multi-warehouse fulfillment and regional inventory allocation algorithms require specialized data stores or domain logic.
2. Single-row lock contention on flash-sale items exceeds PostgreSQL transaction throughput limits, justifying the operational complexity of a Redis/DynamoDB-backed token-bucket or TTL reservation engine.
