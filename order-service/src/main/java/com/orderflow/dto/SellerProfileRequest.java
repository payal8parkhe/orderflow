package com.orderflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SellerProfileRequest {
    @NotBlank(message = "Business name is required")
    private String businessName;
    private String contactEmail;
    private String phoneNumber;
    private String taxId;
    private String payoutDetails;
}
