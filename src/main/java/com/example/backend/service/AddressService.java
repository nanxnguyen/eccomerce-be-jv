package com.example.backend.service;

import com.example.backend.dto.AddressRequest; // AddressRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.AddressResponse; // AddressResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.entity.Address; // Address (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.User; // User (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.exception.ResourceNotFoundException; // ResourceNotFoundException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.repository.AddressRepository; // AddressRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.UserRepository; // UserRepository (repository truy vấn/lưu dữ liệu qua JPA).
import org.springframework.stereotype.Service; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Service).
import org.springframework.transaction.annotation.Transactional; // quản lý transaction database (Transactional).

import java.util.List; // danh sách phần tử cùng kiểu.

@Service
public class AddressService {

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;

    public AddressService(AddressRepository addressRepository, UserRepository userRepository) {
        this.addressRepository = addressRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> list(String email) {
        User user = resolveUser(email);
        return addressRepository.findByUserId(user.getId()).stream().map(AddressResponse::from).toList();
    }

    // Địa chỉ đầu tiên của user tự động là default - user không cần thêm 1 lượt gọi setDefault()
    // riêng chỉ để đánh dấu địa chỉ duy nhất họ có.
    @Transactional
    public AddressResponse create(String email, AddressRequest request) {
        User user = resolveUser(email);
        boolean isFirstAddress = addressRepository.findByUserId(user.getId()).isEmpty();

        Address address = Address.builder()
                .user(user)
                .recipientName(request.recipientName())
                .phone(request.phone())
                .addressLine(request.addressLine())
                .ward(request.ward())
                .district(request.district())
                .province(request.province())
                .isDefault(isFirstAddress)
                .build();

        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional
    public AddressResponse update(String email, Long addressId, AddressRequest request) {
        Address address = findOwned(email, addressId);
        address.setRecipientName(request.recipientName());
        address.setPhone(request.phone());
        address.setAddressLine(request.addressLine());
        address.setWard(request.ward());
        address.setDistrict(request.district());
        address.setProvince(request.province());
        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional
    public void delete(String email, Long addressId) {
        addressRepository.delete(findOwned(email, addressId));
    }

    @Transactional
    public AddressResponse setDefault(String email, Long addressId) {
        User user = resolveUser(email);
        Address target = findOwned(email, addressId);

        addressRepository.findDefaultByUserId(user.getId())
                .filter(current -> !current.getId().equals(target.getId()))
                .ifPresent(current -> {
                    current.setDefault(false);
                    addressRepository.save(current);
                });

        target.setDefault(true);
        return AddressResponse.from(addressRepository.save(target));
    }

    private User resolveUser(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private Address findOwned(String email, Long addressId) {
        User user = resolveUser(email);
        return addressRepository.findByIdAndUserId(addressId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found: " + addressId));
    }
}
