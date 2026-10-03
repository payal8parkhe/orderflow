package com.orderflow.controller;

import com.orderflow.kafka.GatewayEventTelemetryListener;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GatewayTelemetryController {

    private final GatewayEventTelemetryListener telemetryListener;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/events/recent")
    public ResponseEntity<List<Map<String, Object>>> getRecentEvents() {
        return ResponseEntity.ok(telemetryListener.getRecentEvents());
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "OrderFlow Distributed Microservices Gateway");
        health.put("version", "2.0.0-MICROSERVICES");
        health.put("timestamp", LocalDateTime.now().toString());
        health.put("services", Map.of(
                "orderService", "http://localhost:8081",
                "paymentService", "http://localhost:8082",
                "deliveryService", "http://localhost:8083",
                "notificationService", "http://localhost:8084",
                "catalogService", "http://localhost:8085"
        ));
        health.put("components", Map.of(
                "database", "PostgreSQL (Multi-Database Cluster)",
                "cache", "Redis (Distributed Cluster)",
                "messaging", "Apache Kafka KRaft (Event Choreography Active)"
        ));
        return ResponseEntity.ok(health);
    }
}
