package com.orderflow.config;

import com.orderflow.dto.OrderEventPayload;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
@EnableKafka
public class KafkaConfig {

    public static final String ORDER_EVENTS_TOPIC = "order-events";
    public static final String ORDER_EVENTS_DLT_TOPIC = "order-events.DLT";
    public static final String INVENTORY_EVENTS_TOPIC = "inventory-events";
    public static final String CART_EVENTS_TOPIC = "cart-events";
    public static final String PAYMENT_EVENTS_TOPIC = "payment-events";
    public static final String PAYMENT_EVENTS_DLT_TOPIC = "payment-events.DLT";
    public static final String DELIVERY_EVENTS_TOPIC = "delivery-events";
    public static final String DELIVERY_EVENTS_DLT_TOPIC = "delivery-events.DLT";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(ORDER_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic orderEventsDltTopic() {
        return TopicBuilder.name(ORDER_EVENTS_DLT_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic inventoryEventsTopic() {
        return TopicBuilder.name(INVENTORY_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic cartEventsTopic() {
        return TopicBuilder.name(CART_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentEventsDltTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS_DLT_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic deliveryEventsTopic() {
        return TopicBuilder.name(DELIVERY_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic deliveryEventsDltTopic() {
        return TopicBuilder.name(DELIVERY_EVENTS_DLT_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public org.springframework.kafka.listener.CommonErrorHandler commonErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        org.springframework.kafka.listener.DeadLetterPublishingRecoverer recoverer =
                new org.springframework.kafka.listener.DeadLetterPublishingRecoverer(kafkaTemplate,
                        (record, ex) -> {
                            log.error("Consumer retries exhausted for [topic={}, key={}]. Routing poisoned record to DLT: {}",
                                    record.topic(), record.key(), ex.getMessage());
                            return new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", record.partition());
                        });

        org.springframework.kafka.support.ExponentialBackOffWithMaxRetries backOff =
                new org.springframework.kafka.support.ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10000L);

        return new org.springframework.kafka.listener.DefaultErrorHandler(recoverer, backOff);
    }
}
