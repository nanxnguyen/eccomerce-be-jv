package com.example.backend.repository;

import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void savesAndFindsUserByEmail() {
        User user = User.builder()
                .name("Nhut Nguyen")
                .email("nhut@example.com")
                .passwordHash("hashed")
                .role(Role.CUSTOMER)
                .build();

        userRepository.save(user);

        assertThat(userRepository.findByEmail("nhut@example.com")).isPresent();
        assertThat(userRepository.existsByEmail("nhut@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("missing@example.com")).isFalse();
    }

    @Test
    void rejectsDuplicateEmail() {
        userRepository.save(User.builder()
                .name("First")
                .email("dup@example.com")
                .passwordHash("hashed")
                .role(Role.CUSTOMER)
                .build());

        User duplicate = User.builder()
                .name("Second")
                .email("dup@example.com")
                .passwordHash("hashed")
                .role(Role.CUSTOMER)
                .build();

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
