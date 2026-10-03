package com.orderflow.concurrency;

import com.orderflow.model.*;
import com.orderflow.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency test proving that {@code PESSIMISTIC_WRITE} lock prevents overselling.
 *
 * Scenario: 5 units available. 10 threads each try to buy 1 unit simultaneously.
 * Expected: exactly 5 succeed, 5 fail with insufficient stock, 0 overselling.
 *
 * Uses @DataJpaTest (H2 in-memory) to exercise real JPA pessimistic-lock semantics
 * without requiring a running PostgreSQL instance.
 *
 * NOTE: Propagation.NOT_SUPPORTED ensures the test thread does not hold an uncommitted
 * transaction across thread boundaries, allowing worker threads to see committed test data.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class InventoryPessimisticLockConcurrencyTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long productId;
    private Long inventoryId;

    private static final int AVAILABLE_UNITS = 5;
    private static final int TOTAL_THREADS   = 10;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            Product product = Product.builder()
                    .sku("SKU-LOCK-TEST")
                    .name("Lock Test Widget")
                    .price(new BigDecimal("10.00"))
                    .build();
            Product saved = productRepository.save(product);
            productId = saved.getId();

            Inventory inventory = Inventory.builder()
                    .product(saved)
                    .availableStock(AVAILABLE_UNITS)
                    .reservedStock(0)
                    .version(0L)
                    .build();
            Inventory savedInv = inventoryRepository.save(inventory);
            inventoryId = savedInv.getId();
            return null;
        });
    }

    @Test
    @DisplayName("10 concurrent purchase attempts on 5-unit stock: exactly 5 succeed, 0 oversold")
    void pessimisticLock_prevents_overselling() throws InterruptedException {
        AtomicInteger successCount  = new AtomicInteger(0);
        AtomicInteger failureCount  = new AtomicInteger(0);
        List<Exception> errors      = new CopyOnWriteArrayList<>();

        int threadCount = TOTAL_THREADS;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);
        ExecutorService executor  = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // All threads burst simultaneously
                    TransactionTemplate tx = new TransactionTemplate(transactionManager);
                    boolean purchased = Boolean.TRUE.equals(tx.execute(status -> {
                        // Acquire pessimistic write lock — equivalent to what OrderService does
                        Inventory inv = inventoryRepository
                                .findByProductIdWithLock(productId)
                                .orElseThrow(() -> new IllegalStateException("Inventory missing"));

                        if (inv.getAvailableStock() < 1) {
                            return false; // Out of stock
                        }
                        inv.setAvailableStock(inv.getAvailableStock() - 1);
                        inv.setReservedStock(inv.getReservedStock() + 1);
                        inventoryRepository.save(inv);
                        return true;
                    }));

                    if (Boolean.TRUE.equals(purchased)) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.add(e);
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Fire all threads at once
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Read final inventory state
        TransactionTemplate readTx = new TransactionTemplate(transactionManager);
        readTx.setReadOnly(true);
        Inventory finalInventory = readTx.execute(status ->
                inventoryRepository.findById(inventoryId).orElseThrow());

        // Core assertion: zero overselling
        assertThat(successCount.get())
                .as("Exactly AVAILABLE_UNITS purchases should succeed")
                .isEqualTo(AVAILABLE_UNITS);

        assertThat(failureCount.get())
                .as("The remaining " + (TOTAL_THREADS - AVAILABLE_UNITS) + " threads should be rejected")
                .isEqualTo(TOTAL_THREADS - AVAILABLE_UNITS);

        assertThat(finalInventory.getAvailableStock())
                .as("Available stock must be exactly 0 after all purchases — no overselling")
                .isEqualTo(0);

        assertThat(finalInventory.getReservedStock())
                .as("Reserved stock must equal units sold")
                .isEqualTo(AVAILABLE_UNITS);

        if (!errors.isEmpty()) {
            // Unexpected runtime exceptions (not stock failures) — fail loudly
            throw new AssertionError("Unexpected concurrency exceptions: " + errors.get(0).getMessage(), errors.get(0));
        }
    }
}
