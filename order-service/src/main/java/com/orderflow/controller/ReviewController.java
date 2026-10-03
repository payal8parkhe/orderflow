package com.orderflow.controller;

import com.orderflow.dto.ReviewRequest;
import com.orderflow.dto.ReviewResponse;
import com.orderflow.dto.ReviewSummaryResponse;
import com.orderflow.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products/{productId}/reviews")
@RequiredArgsConstructor
@Tag(name = "Product Reviews", description = "Customer ratings, verified purchase tags, and comments on products")
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "Get reviews for a product", description = "Returns customer reviews, average rating, and verified purchase flags")
    @GetMapping
    public ResponseEntity<ReviewSummaryResponse> getReviewsForProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(reviewService.getReviewsForProduct(productId));
    }

    @Operation(summary = "Submit review for a product", description = "Allows authenticated customer to submit rating and comment. Automatically verifies purchase history.")
    @PostMapping
    public ResponseEntity<ReviewResponse> addReview(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long productId,
            @Valid @RequestBody ReviewRequest request) {
        ReviewResponse response = reviewService.addReview(userDetails.getUsername(), productId, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
}
