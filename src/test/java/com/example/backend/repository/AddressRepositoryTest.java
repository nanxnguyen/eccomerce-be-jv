package com.example.backend.repository;

import com.example.backend.entity.Address;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AddressRepositoryTest {

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void savesFindsByUserAndTracksDefault() {
        User user = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com").passwordHash("hashed").role(Role.CUSTOMER).build());
        User other = userRepository.save(User.builder()
                .name("Other").email("other@example.com").passwordHash("hashed").role(Role.CUSTOMER).build());

        Address home = addressRepository.save(Address.builder()
                .user(user).recipientName("Nhut").phone("0900000000").addressLine("123 Main St")
                .ward("Ward 1").district("District 1").province("HCMC").isDefault(true).build());
        addressRepository.save(Address.builder()
                .user(user).recipientName("Nhut").phone("0900000000").addressLine("456 Side St")
                .isDefault(false).build());
        addressRepository.save(Address.builder()
                .user(other).recipientName("Other").phone("0911111111").addressLine("789 Other St")
                .isDefault(true).build());

        assertThat(addressRepository.findByUserId(user.getId())).hasSize(2);
        assertThat(addressRepository.findByIdAndUserId(home.getId(), user.getId())).isPresent();
        assertThat(addressRepository.findByIdAndUserId(home.getId(), other.getId())).isEmpty();
        assertThat(addressRepository.findDefaultByUserId(user.getId())).contains(home);
    }
}
