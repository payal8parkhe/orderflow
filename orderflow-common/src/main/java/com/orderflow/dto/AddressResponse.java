package com.orderflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressResponse {

    private Long id;
    private Long userId;
    private String recipientName;
    private String phoneNumber;
    private String streetAddress;
    private String apartmentSuite;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    @com.fasterxml.jackson.annotation.JsonProperty("isDefault")
    private boolean isDefault;
    private LocalDateTime createdAt;
}
