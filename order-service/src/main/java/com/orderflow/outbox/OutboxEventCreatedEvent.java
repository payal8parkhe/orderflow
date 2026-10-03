package com.orderflow.outbox;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OutboxEventCreatedEvent {
    private final Long outboxEventId;
}
