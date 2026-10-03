-- Flyway Migration V1: Initial Schema for Payment Service

CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE,
    order_number VARCHAR(64),
    user_id BIGINT,
    user_email VARCHAR(100),
    payment_method VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    transaction_reference VARCHAR(64) NOT NULL UNIQUE,
    amount NUMERIC(10, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    refund_reference VARCHAR(64),
    paid_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_payment_order ON payments(order_id);
CREATE INDEX IF NOT EXISTS idx_payment_tx_ref ON payments(transaction_reference);

CREATE TABLE IF NOT EXISTS processed_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    event_type VARCHAR(100),
    processed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uk_payment_processed_event_group UNIQUE (event_id, consumer_group)
);
