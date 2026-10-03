package com.orderflow.repository;

import com.orderflow.model.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByOrderId(Long orderId);

    Optional<Delivery> findByTrackingNumber(String trackingNumber);

    List<Delivery> findByDeliveryAgentIdOrderByAssignedAtDesc(Long deliveryAgentId);

    List<Delivery> findByDeliveryAgentEmailOrderByAssignedAtDesc(String deliveryAgentEmail);
}
