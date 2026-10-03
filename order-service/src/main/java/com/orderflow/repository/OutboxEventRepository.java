package com.orderflow.repository;

import com.orderflow.model.OutboxEvent;
import com.orderflow.model.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    @Query("SELECT o FROM OutboxEvent o WHERE o.status = :status OR (o.status = 'FAILED' AND o.retryCount < :maxRetries) ORDER BY o.createdAt ASC")
    List<OutboxEvent> findPendingOrRetryable(@Param("status") OutboxStatus status, @Param("maxRetries") int maxRetries, Pageable pageable);

    long countByStatus(OutboxStatus status);

    @Query("DELETE FROM OutboxEvent o WHERE o.status = 'PUBLISHED' AND o.publishedAt < :cutoff")
    void deletePublishedOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
