package com.example.backend.dto;

import com.example.backend.entity.Address; // Address (entity ánh xạ dữ liệu với bảng database).

// DTO response: chỉ đưa thông tin địa chỉ cần cho client, không trả trực tiếp entity JPA.
public record AddressResponse(
        Long id, // ID để client chọn địa chỉ khi đặt hàng.
        String recipientName, // Người nhận.
        String phone, // Số liên hệ.
        String addressLine, // Địa chỉ chi tiết.
        String ward, // Phường/xã.
        String district, // Quận/huyện.
        String province, // Tỉnh/thành.
        boolean isDefault // Địa chỉ mặc định của tài khoản hay không.
) {
    // Tạo dữ liệu response từ entity đã đọc hoặc lưu trong database.
    public static AddressResponse from(Address address) {
        return new AddressResponse(
                // Lấy từng giá trị từ entity; record sẽ serialize thành các trường JSON cùng tên.
                address.getId(), address.getRecipientName(), address.getPhone(), address.getAddressLine(),
                address.getWard(), address.getDistrict(), address.getProvince(), address.isDefault()
        );
    }
}
