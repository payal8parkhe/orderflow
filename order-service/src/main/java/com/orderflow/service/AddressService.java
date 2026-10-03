package com.orderflow.service;

import com.orderflow.dto.AddressRequest;
import com.orderflow.dto.AddressResponse;
import com.orderflow.exception.BadRequestException;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.model.Address;
import com.orderflow.model.OrderAddress;
import com.orderflow.model.User;
import com.orderflow.repository.AddressRepository;
import com.orderflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AddressService {

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<AddressResponse> getUserAddresses(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));
        return addressRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public AddressResponse addAddress(String userEmail, AddressRequest request) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        List<Address> existing = addressRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        boolean makeDefault = existing.isEmpty() || Boolean.TRUE.equals(request.getIsDefault());

        if (makeDefault) {
            existing.forEach(a -> {
                if (a.isDefault()) {
                    a.setDefault(false);
                    addressRepository.save(a);
                }
            });
        }

        Address address = Address.builder()
                .user(user)
                .recipientName(request.getRecipientName())
                .phoneNumber(request.getPhoneNumber())
                .streetAddress(request.getStreetAddress())
                .apartmentSuite(request.getApartmentSuite())
                .city(request.getCity())
                .state(request.getState())
                .postalCode(request.getPostalCode())
                .country(request.getCountry())
                .isDefault(makeDefault)
                .build();

        Address saved = addressRepository.save(address);
        log.info("Saved new shipping address #{} for user {}", saved.getId(), userEmail);
        return mapToResponse(saved);
    }

    @Transactional
    public AddressResponse setDefaultAddress(String userEmail, Long addressId) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        Address target = addressRepository.findByIdAndUserId(addressId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));

        List<Address> addresses = addressRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        for (Address a : addresses) {
            a.setDefault(a.getId().equals(addressId));
            addressRepository.save(a);
        }

        return mapToResponse(target);
    }

    @Transactional
    public void deleteAddress(String userEmail, Long addressId) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        Address address = addressRepository.findByIdAndUserId(addressId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));

        addressRepository.delete(address);
        log.info("Deleted address #{} for user {}", addressId, userEmail);
    }

    @Transactional(readOnly = true)
    public OrderAddress resolveOrderAddress(User user, Long addressId, AddressRequest customAddress) {
        if (addressId != null) {
            return addressRepository.findByIdAndUserId(addressId, user.getId())
                    .map(Address::toOrderAddress)
                    .orElseThrow(() -> new BadRequestException("Specified address id does not exist: " + addressId));
        }

        if (customAddress != null && customAddress.getStreetAddress() != null && !customAddress.getStreetAddress().isBlank()) {
            return OrderAddress.builder()
                    .recipientName(customAddress.getRecipientName())
                    .phoneNumber(customAddress.getPhoneNumber())
                    .streetAddress(customAddress.getStreetAddress())
                    .apartmentSuite(customAddress.getApartmentSuite())
                    .city(customAddress.getCity())
                    .state(customAddress.getState())
                    .postalCode(customAddress.getPostalCode())
                    .country(customAddress.getCountry())
                    .build();
        }

        // Fallback to user's default saved address
        return addressRepository.findByUserIdAndIsDefaultTrue(user.getId())
                .map(Address::toOrderAddress)
                .orElse(null);
    }

    private AddressResponse mapToResponse(Address a) {
        return AddressResponse.builder()
                .id(a.getId())
                .userId(a.getUser().getId())
                .recipientName(a.getRecipientName())
                .phoneNumber(a.getPhoneNumber())
                .streetAddress(a.getStreetAddress())
                .apartmentSuite(a.getApartmentSuite())
                .city(a.getCity())
                .state(a.getState())
                .postalCode(a.getPostalCode())
                .country(a.getCountry())
                .isDefault(a.isDefault())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
