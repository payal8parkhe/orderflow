package com.orderflow.repository;

import com.orderflow.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByEventIdAndConsumerGroup(String eventId, String consumerGroup);
    Optional<ProcessedEvent> findByEventIdAndConsumerGroup(String eventId, String consumerGroup);
}
