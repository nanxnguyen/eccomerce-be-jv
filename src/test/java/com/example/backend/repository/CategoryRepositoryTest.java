package com.example.backend.repository;

import com.example.backend.entity.Category;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class CategoryRepositoryTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void savesAndFindsBySlug() {
        categoryRepository.save(Category.builder().name("Fashion").slug("fashion").description("Clothes").build());

        assertThat(categoryRepository.findBySlug("fashion")).isPresent();
        assertThat(categoryRepository.existsBySlug("fashion")).isTrue();
        assertThat(categoryRepository.existsBySlug("missing")).isFalse();
    }

    @Test
    void rejectsDuplicateSlug() {
        categoryRepository.save(Category.builder().name("Fashion").slug("fashion").description("Clothes").build());

        Category duplicate = Category.builder().name("Fashion 2").slug("fashion").description("Other").build();

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
