-- Flyway Migration V1: Initial Schema for Delivery Service

CREATE TABLE IF NOT EXISTS deliveries (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE,
    order_number VARCHAR(64),
    delivery_agent_id BIGINT NOT NULL,
    delivery_agent_name VARCHAR(150),
    delivery_agent_email VARCHAR(100),
    status VARCHAR(30) NOT NULL,
    tracking_number VARCHAR(64) NOT NULL UNIQUE,
    assigned_at TIMESTAMP WITHOUT TIME ZONE,
    picked_up_at TIMESTAMP WITHOUT TIME ZONE,
    delivered_at TIMESTAMP WITHOUT TIME ZONE,
    recipient_proof_name VARCHAR(150),
    failure_reason VARCHAR(255),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    street_address VARCHAR(255),
    apartment_suite VARCHAR(100),
    city VARCHAR(100),
    state VARCHAR(100),
    postal_code VARCHAR(20),
    country VARCHAR(100),
    recipient_name VARCHAR(150),
    phone VARCHAR(50),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_delivery_order ON deliveries(order_id);
CREATE INDEX IF NOT EXISTS idx_delivery_agent ON deliveries(delivery_agent_id);
CREATE INDEX IF NOT EXISTS idx_delivery_tracking ON deliveries(tracking_number);

CREATE TABLE IF NOT EXISTS processed_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    event_type VARCHAR(100),
    processed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uk_delivery_processed_event_group UNIQUE (event_id, consumer_group)
);
