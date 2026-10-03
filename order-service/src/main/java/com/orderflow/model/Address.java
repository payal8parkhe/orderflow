package com.orderflow.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "addresses", indexes = {
        @Index(name = "idx_address_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 150)
    private String recipientName;

    @Column(nullable = false, length = 30)
    private String phoneNumber;

    @Column(nullable = false, length = 255)
    private String streetAddress;

    @Column(length = 100)
    private String apartmentSuite;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 100)
    private String state;

    @Column(nullable = false, length = 20)
    private String postalCode;

    @Column(nullable = false, length = 100)
    private String country;

    @Column(nullable = false)
    private boolean isDefault;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public OrderAddress toOrderAddress() {
        return OrderAddress.builder()
                .recipientName(this.recipientName)
                .phoneNumber(this.phoneNumber)
                .streetAddress(this.streetAddress)
                .apartmentSuite(this.apartmentSuite)
                .city(this.city)
                .state(this.state)
                .postalCode(this.postalCode)
                .country(this.country)
                .build();
    }
}
