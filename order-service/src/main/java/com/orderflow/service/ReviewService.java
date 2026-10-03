package com.orderflow.service;

import com.orderflow.dto.ReviewRequest;
import com.orderflow.dto.ReviewResponse;
import com.orderflow.dto.ReviewSummaryResponse;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.model.Order;
import com.orderflow.model.OrderStatus;
import com.orderflow.model.Product;
import com.orderflow.model.Review;
import com.orderflow.model.User;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.ProductRepository;
import com.orderflow.repository.ReviewRepository;
import com.orderflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    private static final Set<OrderStatus> QUALIFIED_STATUSES = Set.of(
            OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.COMPLETED
    );

    @Transactional(readOnly = true)
    public ReviewSummaryResponse getReviewsForProduct(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product not found with id: " + productId);
        }

        List<Review> reviews = reviewRepository.findByProductIdOrderByCreatedAtDesc(productId);
        double avg = 0.0;
        if (!reviews.isEmpty()) {
            double sum = reviews.stream().mapToInt(Review::getRating).sum();
            avg = BigDecimal.valueOf(sum / reviews.size())
                    .setScale(1, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        List<ReviewResponse> mapped = reviews.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return ReviewSummaryResponse.builder()
                .productId(productId)
                .averageRating(avg)
                .totalReviews(reviews.size())
                .reviews(mapped)
                .build();
    }

    @Transactional
    public ReviewResponse addReview(String userEmail, Long productId, ReviewRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + userEmail));

        // Check for verified purchase: Did user purchase this product in a CONFIRMED, SHIPPED, or COMPLETED order?
        List<Order> userOrders = orderRepository.findByUserId(user.getId(), PageRequest.of(0, 100)).getContent();
        Optional<Order> matchingOrder = userOrders.stream()
                .filter(o -> QUALIFIED_STATUSES.contains(o.getStatus()))
                .filter(o -> o.getItems().stream().anyMatch(item -> item.getProduct().getId().equals(productId)))
                .findFirst();

        boolean isVerified = matchingOrder.isPresent();
        Long orderId = matchingOrder.map(Order::getId).orElse(null);

        Review review = Review.builder()
                .productId(productId)
                .userId(user.getId())
                .userFullName(user.getFullName())
                .orderId(orderId)
                .rating(request.getRating())
                .comment(request.getComment())
                .verifiedPurchase(isVerified)
                .build();

        Review saved = reviewRepository.save(review);
        log.info("Saved review #{} for product #{} by user {} (Verified purchase: {})",
                saved.getId(), productId, userEmail, isVerified);

        return mapToResponse(saved);
    }

    private ReviewResponse mapToResponse(Review r) {
        return ReviewResponse.builder()
                .id(r.getId())
                .productId(r.getProductId())
                .userId(r.getUserId())
                .userFullName(r.getUserFullName())
                .rating(r.getRating())
                .comment(r.getComment())
                .verifiedPurchase(r.getVerifiedPurchase())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
