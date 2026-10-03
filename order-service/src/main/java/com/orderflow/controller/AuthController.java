package com.orderflow.controller;

import com.orderflow.dto.AuthResponse;
import com.orderflow.dto.LoginRequest;
import com.orderflow.dto.RegisterRequest;
import com.orderflow.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Endpoints for user registration and JWT authentication token issuance")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register a new user", description = "Creates a new customer or admin user and returns a JWT token")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @Operation(summary = "Register a new seller/merchant", description = "Creates a new vendor account with ROLE_SELLER and returns a JWT token")
    @PostMapping("/register-seller")
    public ResponseEntity<AuthResponse> registerSeller(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.registerSeller(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @Operation(summary = "Authenticate user", description = "Authenticates credentials and returns a Bearer JWT token")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }
}
