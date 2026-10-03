package com.orderflow.repository;

import com.orderflow.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    @Query("SELECT i FROM OrderItem i JOIN FETCH i.order o JOIN FETCH i.product p WHERE p.sellerId = :sellerId ORDER BY o.createdAt DESC")
    List<OrderItem> findByProductSellerId(@Param("sellerId") Long sellerId);
}
