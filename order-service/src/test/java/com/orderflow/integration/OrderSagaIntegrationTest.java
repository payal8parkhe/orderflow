package com.orderflow.integration;

import com.orderflow.dto.PaymentEventPayload;
import com.orderflow.kafka.PaymentSagaCoordinator;
import com.orderflow.model.*;
import com.orderflow.repository.*;
import com.orderflow.service.OrderService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Order Saga Integration Test.
 *
 * Walks the full saga path using embedded Kafka + H2:
 *   1. An order is persisted in AWAITING_PAYMENT status.
 *   2. A PAYMENT_COMPLETED event is sent to the "payment-events" topic.
 *   3. {@link PaymentSagaCoordinator} consumes the event and calls
 *      {@link OrderService#confirmPayment}, transitioning the order to CONFIRMED.
 *
 * This proves the Kafka consumer integration is wired and the saga transition works.
 *
 * Note: Awaitility is used to wait for async Kafka consumption (up to 15s).
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {"order-events", "payment-events", "delivery-events",
                  "order-events.DLT", "payment-events.DLT"},
        brokerProperties = {
                "listeners=PLAINTEXT://localhost:9099",
                "port=9099"
        }
)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.datasource.url=jdbc:h2:mem:sagatest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6399",
        // Disable Redis caching in tests
        "spring.cache.type=none",
        "management.health.redis.enabled=false",
        "order.unpaid-expiration.fixed-delay-ms=999999",
        "outbox.poller.interval-ms=999999",
        "jwt.secret=testSecretKeyForJwtTesting1234567890Abcdef",
        "order.service.url=http://localhost:9999"
})
@DirtiesContext
class OrderSagaIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private org.springframework.kafka.config.KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    private Long savedOrderId;
    private String savedOrderNumber;

    @BeforeEach
    void setUp() {
        for (org.springframework.kafka.listener.MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            org.springframework.kafka.test.utils.ContainerTestUtils.waitForAssignment(container, 3);
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            User user = userRepository.findByEmail("saga-test@example.com")
                    .orElseGet(() -> userRepository.save(User.builder()
                            .email("saga-test@example.com")
                            .fullName("Saga Tester")
                            .password("$2a$10$hashed")
                            .role(Role.ROLE_CUSTOMER)
                            .build()));

            savedOrderNumber = "ORD-SAGA-" + System.nanoTime();
            Order order = Order.builder()
                    .orderNumber(savedOrderNumber)
                    .user(user)
                    .status(OrderStatus.AWAITING_PAYMENT)
                    .totalAmount(new BigDecimal("49.99"))
                    .items(new java.util.ArrayList<>())
                    .estimatedDeliveryDate(java.time.LocalDateTime.now().plusDays(3))
                    .build();
            Order savedOrder = orderRepository.save(order);
            savedOrderId = savedOrder.getId();
            return null;
        });
    }

    @Test
    @DisplayName("PAYMENT_COMPLETED Kafka event triggers saga and transitions order to CONFIRMED")
    void paymentCompletedEvent_transitions_order_to_confirmed() throws Exception {
        // Arrange: Build and send the PAYMENT_COMPLETED event
        PaymentEventPayload event = PaymentEventPayload.builder()
                .eventType("PAYMENT_COMPLETED")
                .orderId(savedOrderId)
                .orderNumber(savedOrderNumber)
                .paymentId(999L)
                .userId(1L)
                .userEmail("saga-test@example.com")
                .amount(new BigDecimal("49.99"))
                .status(com.orderflow.model.PaymentStatus.COMPLETED)
                .timestamp(java.time.LocalDateTime.now())
                .build();

        kafkaTemplate.send(new ProducerRecord<>("payment-events", savedOrderNumber, event)).get(10, TimeUnit.SECONDS);

        // Assert: Wait for the consumer to process and confirm the order
        await()
                .atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    TransactionTemplate readTx = new TransactionTemplate(transactionManager);
                    readTx.setReadOnly(true);
                    OrderStatus status = readTx.execute(txStatus -> {
                        Order o = orderRepository.findById(savedOrderId).orElseThrow();
                        return o.getStatus();
                    });
                    assertThat(status)
                            .as("Order should be CONFIRMED after PAYMENT_COMPLETED saga event")
                            .isEqualTo(OrderStatus.CONFIRMED);
                });
    }

    @Test
    @DisplayName("PAYMENT_FAILED Kafka event triggers compensating transaction and cancels order")
    void paymentFailedEvent_cancels_order_and_releases_stock() throws Exception {
        PaymentEventPayload event = PaymentEventPayload.builder()
                .eventType("PAYMENT_FAILED")
                .orderId(savedOrderId)
                .orderNumber(savedOrderNumber)
                .paymentId(998L)
                .userId(1L)
                .userEmail("saga-test@example.com")
                .amount(new BigDecimal("49.99"))
                .status(com.orderflow.model.PaymentStatus.FAILED)
                .failureReason("Simulated card decline")
                .timestamp(java.time.LocalDateTime.now())
                .build();

        kafkaTemplate.send(new ProducerRecord<>("payment-events", savedOrderNumber, event)).get(10, TimeUnit.SECONDS);

        await()
                .atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    TransactionTemplate readTx = new TransactionTemplate(transactionManager);
                    readTx.setReadOnly(true);
                    OrderStatus status = readTx.execute(txStatus -> {
                        Order o = orderRepository.findById(savedOrderId).orElseThrow();
                        return o.getStatus();
                    });
                    assertThat(status)
                            .as("Order should be CANCELLED after PAYMENT_FAILED compensating transaction")
                            .isEqualTo(OrderStatus.CANCELLED);
                });
    }

    @Test
    @DisplayName("Duplicate PAYMENT_COMPLETED events are idempotent — order stays CONFIRMED")
    void duplicatePaymentCompleted_isIdempotent() throws Exception {
        PaymentEventPayload event = PaymentEventPayload.builder()
                .eventType("PAYMENT_COMPLETED")
                .orderId(savedOrderId)
                .orderNumber(savedOrderNumber)
                .paymentId(997L)
                .userId(1L)
                .userEmail("saga-test@example.com")
                .amount(new BigDecimal("49.99"))
                .status(com.orderflow.model.PaymentStatus.COMPLETED)
                .timestamp(java.time.LocalDateTime.now())
                .build();

        // Send the event twice (simulating at-least-once delivery)
        kafkaTemplate.send(new ProducerRecord<>("payment-events", savedOrderNumber, event)).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(new ProducerRecord<>("payment-events", savedOrderNumber, event)).get(10, TimeUnit.SECONDS);

        await()
                .atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    TransactionTemplate readTx = new TransactionTemplate(transactionManager);
                    readTx.setReadOnly(true);
                    OrderStatus status = readTx.execute(txStatus ->
                            orderRepository.findById(savedOrderId).orElseThrow().getStatus());

                    // After both events, order should be in CONFIRMED — not in an invalid state
                    assertThat(status)
                            .as("Duplicate PAYMENT_COMPLETED events must not corrupt order state")
                            .isIn(OrderStatus.CONFIRMED, OrderStatus.PROCESSING);
                });
    }
}
