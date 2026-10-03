package com.orderflow.controller;

import com.orderflow.dto.AdminUserCreateRequest;
import com.orderflow.dto.UserResponse;
import com.orderflow.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Administration", description = "Privileged administrator operations and role-based user management")
public class AdminController {

    private final AuthService authService;

    @Operation(summary = "Create administrative or privileged user", description = "Allows an existing authenticated administrator to provision admin or staff accounts")
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> createAdminUser(@Valid @RequestBody AdminUserCreateRequest request) {
        UserResponse response = authService.createAdminUser(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
}
