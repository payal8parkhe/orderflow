package com.orderflow.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.dto.OrderEventPayload;
import com.orderflow.model.OutboxEvent;
import com.orderflow.model.OutboxStatus;
import com.orderflow.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Persist outbox event within the caller's active database transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent saveEvent(String aggregateType, String aggregateId, String eventType, String topic, Object payload) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .topic(topic)
                    .payload(jsonPayload)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .createdAt(LocalDateTime.now())
                    .build();

            OutboxEvent saved = outboxEventRepository.save(outboxEvent);
            applicationEventPublisher.publishEvent(new OutboxEventCreatedEvent(saved.getId()));
            log.debug("Enqueued outbox event #{} [{} - {}]", saved.getId(), aggregateType, eventType);
            return saved;
        } catch (Exception e) {
            log.error("Failed to enqueue outbox event for aggregate {}: {}", aggregateId, e.getMessage(), e);
            throw new RuntimeException("Failed to persist outbox event", e);
        }
    }

    /**
     * Immediate dispatch hook right after the enclosing database transaction commits.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionCommit(OutboxEventCreatedEvent event) {
        log.debug("Transaction committed for outbox event #{}. Initiating immediate dispatch.", event.getOutboxEventId());
        dispatchSingleEvent(event.getOutboxEventId());
    }

    /**
     * Periodic background poller to ensure stuck or failed events are safely re-dispatched.
     */
    @Scheduled(fixedDelayString = "${outbox.poller.interval-ms:3000}")
    public void pollPendingOutboxEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingOrRetryable(
                OutboxStatus.PENDING, 5, PageRequest.of(0, 50)
        );

        if (!pendingEvents.isEmpty()) {
            log.debug("Outbox poller found {} pending/retryable events to dispatch", pendingEvents.size());
            for (OutboxEvent event : pendingEvents) {
                dispatchSingleEvent(event.getId());
            }
        }
    }

    @Transactional
    public void dispatchSingleEvent(Long eventId) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            if (event.getStatus() == OutboxStatus.PUBLISHED) {
                return;
            }

            try {
                Object payloadObj;
                if ("order-events".equals(event.getTopic())) {
                    payloadObj = objectMapper.readValue(event.getPayload(), OrderEventPayload.class);
                } else {
                    payloadObj = objectMapper.readValue(event.getPayload(), Object.class);
                }

                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), payloadObj)
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                markPublished(eventId);
                            } else {
                                markFailed(eventId, ex.getMessage());
                            }
                        });
            } catch (Exception e) {
                markFailed(eventId, e.getMessage());
            }
        });
    }

    @Transactional
    public void markPublished(Long eventId) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(LocalDateTime.now());
            outboxEventRepository.save(event);
            log.info("Successfully published outbox event #{} [{}] to topic [{}]",
                    event.getId(), event.getEventType(), event.getTopic());
        });
    }

    @Transactional
    public void markFailed(Long eventId, String errorMessage) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            int retries = event.getRetryCount() + 1;
            event.setRetryCount(retries);
            event.setLastError(errorMessage != null && errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage);
            if (retries >= 5) {
                event.setStatus(OutboxStatus.FAILED);
                log.error("Outbox event #{} permanently FAILED after {} retries: {}", event.getId(), retries, errorMessage);
            } else {
                log.warn("Outbox event #{} dispatch attempt {} failed: {}", event.getId(), retries, errorMessage);
            }
            outboxEventRepository.save(event);
        });
    }
}
