package com.example.backend.dto;

import com.example.backend.entity.Address;

public record AddressResponse(
        Long id,
        String recipientName,
        String phone,
        String addressLine,
        String ward,
        String district,
        String province,
        boolean isDefault
) {
    public static AddressResponse from(Address address) {
        return new AddressResponse(
                address.getId(), address.getRecipientName(), address.getPhone(), address.getAddressLine(),
                address.getWard(), address.getDistrict(), address.getProvince(), address.isDefault()
        );
    }
}

