package com.orderflow.controller;

import com.orderflow.dto.AddressRequest;
import com.orderflow.dto.AddressResponse;
import com.orderflow.service.AddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users/me/addresses")
@RequiredArgsConstructor
@Tag(name = "Address Book", description = "Customer shipping address book management for order fulfillment")
public class AddressController {

    private final AddressService addressService;

    @Operation(summary = "Get user saved addresses", description = "Retrieve list of all saved shipping addresses for authenticated customer")
    @GetMapping
    public ResponseEntity<List<AddressResponse>> getAddresses(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(addressService.getUserAddresses(userDetails.getUsername()));
    }

    @Operation(summary = "Add shipping address", description = "Add a new shipping address to the customer's address book")
    @PostMapping
    public ResponseEntity<AddressResponse> addAddress(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody AddressRequest request) {
        AddressResponse response = addressService.addAddress(userDetails.getUsername(), request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @Operation(summary = "Set default address", description = "Set a specific address as the primary default shipping destination")
    @PutMapping("/{id}/default")
    public ResponseEntity<AddressResponse> setDefaultAddress(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        return ResponseEntity.ok(addressService.setDefaultAddress(userDetails.getUsername(), id));
    }

    @Operation(summary = "Delete address", description = "Remove an address from the customer's address book")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAddress(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        addressService.deleteAddress(userDetails.getUsername(), id);
        return ResponseEntity.noContent().build();
    }
}
