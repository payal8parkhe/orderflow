package com.orderflow.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderAddress implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "shipping_recipient_name", length = 150)
    private String recipientName;

    @Column(name = "shipping_phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "shipping_street_address", length = 255)
    private String streetAddress;

    @Column(name = "shipping_apartment_suite", length = 100)
    private String apartmentSuite;

    @Column(name = "shipping_city", length = 100)
    private String city;

    @Column(name = "shipping_state", length = 100)
    private String state;

    @Column(name = "shipping_postal_code", length = 20)
    private String postalCode;

    @Column(name = "shipping_country", length = 100)
    private String country;
}
