package com.orderflow.repository;

import com.orderflow.model.CartItem;
import com.orderflow.model.CartItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<CartItem> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, CartItemStatus status);

    List<CartItem> findByGuestSessionIdOrderByCreatedAtDesc(String guestSessionId);

    List<CartItem> findByGuestSessionIdAndStatusOrderByCreatedAtDesc(String guestSessionId, CartItemStatus status);

    Optional<CartItem> findByUserIdAndProductIdAndStatus(Long userId, Long productId, CartItemStatus status);

    Optional<CartItem> findByGuestSessionIdAndProductIdAndStatus(String guestSessionId, Long productId, CartItemStatus status);

    Optional<CartItem> findByUserIdAndVariantIdAndStatus(Long userId, Long variantId, CartItemStatus status);

    Optional<CartItem> findByGuestSessionIdAndVariantIdAndStatus(String guestSessionId, Long variantId, CartItemStatus status);

    Optional<CartItem> findByUserIdAndProductIdAndVariantIdAndStatus(Long userId, Long productId, Long variantId, CartItemStatus status);

    Optional<CartItem> findByGuestSessionIdAndProductIdAndVariantIdAndStatus(String guestSessionId, Long productId, Long variantId, CartItemStatus status);

    @Modifying
    @Query("DELETE FROM CartItem c WHERE c.user.id = :userId AND c.status = :status")
    void deleteByUserIdAndStatus(@Param("userId") Long userId, @Param("status") CartItemStatus status);

    @Modifying
    @Query("DELETE FROM CartItem c WHERE c.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM CartItem c WHERE c.guestSessionId = :guestSessionId")
    void deleteByGuestSessionId(@Param("guestSessionId") String guestSessionId);

    @Query("SELECT c FROM CartItem c WHERE c.variant IS NULL")
    List<CartItem> findByVariantIsNull();
}
