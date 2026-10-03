package com.orderflow.controller;

import com.orderflow.dto.DeliveryAssignRequest;
import com.orderflow.dto.DeliveryResponse;
import com.orderflow.dto.DeliveryStatusUpdateRequest;
import com.orderflow.service.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/api/delivery", "/api/deliveries"})
@RequiredArgsConstructor
@Tag(name = "Delivery & Fulfillment", description = "Delivery agent dispatch, live route status updates, and proof of fulfillment")
public class DeliveryController {

    private final DeliveryService deliveryService;

    @Operation(summary = "Assign order to delivery agent", description = "Admin assigns an order to a delivery agent, generating tracking number and advancing order to PROCESSING")
    @PostMapping({"/assign", "/admin/assign"})
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DeliveryResponse> assignDelivery(@Valid @RequestBody DeliveryAssignRequest request) {
        DeliveryResponse response = deliveryService.assignDelivery(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @Operation(summary = "Get assigned deliveries for agent", description = "Delivery agent retrieves their queue of assigned shipments")
    @GetMapping({"/my-deliveries", "/my-assignments"})
    @PreAuthorize("hasAnyRole('DELIVERY_AGENT', 'ADMIN')")
    public ResponseEntity<List<DeliveryResponse>> getMyAssignments(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(deliveryService.getMyAssignedDeliveries(userDetails.getUsername()));
    }

    @Operation(summary = "Update delivery status", description = "Delivery agent marks shipment as PICKED_UP, OUT_FOR_DELIVERY, DELIVERED, or FAILED_ATTEMPT")
    @PatchMapping("/{deliveryId}/status")
    @PreAuthorize("hasAnyRole('DELIVERY_AGENT', 'ADMIN')")
    public ResponseEntity<DeliveryResponse> updateStatus(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long deliveryId,
            @Valid @RequestBody DeliveryStatusUpdateRequest request) {
        DeliveryResponse response = deliveryService.updateDeliveryStatus(userDetails.getUsername(), deliveryId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get delivery details for order", description = "Fetch delivery tracking info and current courier status for an order")
    @GetMapping("/order/{orderId}")
    public ResponseEntity<DeliveryResponse> getDeliveryByOrderId(@PathVariable Long orderId) {
        return ResponseEntity.ok(deliveryService.getDeliveryByOrderId(orderId));
    }
}
