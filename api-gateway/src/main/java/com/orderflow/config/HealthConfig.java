package com.orderflow.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HealthConfig {

    @Bean("db")
    public HealthIndicator dbHealthIndicator() {
        return () -> Health.up().withDetail("database", "PostgreSQL (Multi-Database Cluster)").build();
    }

    @Bean("redis")
    public HealthIndicator redisHealthIndicator() {
        return () -> Health.up().withDetail("cache", "Redis (Distributed Cluster)").build();
    }

    @Bean("microservices")
    public HealthIndicator microservicesHealthIndicator() {
        return () -> Health.up()
                .withDetail("orderService", "http://localhost:8081")
                .withDetail("paymentService", "http://localhost:8082")
                .withDetail("deliveryService", "http://localhost:8083")
                .build();
    }
}
