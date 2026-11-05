package com.aiprofessor.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiprofessor.backend.TestcontainersConfiguration;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.domain.UserRole;
import com.aiprofessor.backend.auth.domain.UserStatus;
import com.aiprofessor.backend.support.config.JpaConfig;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaConfig.class})
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 사용자를_저장하고_식별자로_조회한다() {
        UUID userId = UUID.randomUUID();
        User user = createUser(userId, "learner@example.com");

        userRepository.saveAndFlush(user);
        entityManager.clear();

        User foundUser = userRepository.findById(userId).orElseThrow();

        assertThat(ReflectionTestUtils.getField(foundUser, "email"))
                .isEqualTo("learner@example.com");
        assertThat(ReflectionTestUtils.getField(foundUser, "passwordHash"))
                .isEqualTo("{bcrypt}encoded-password");
        assertThat(ReflectionTestUtils.getField(foundUser, "displayName"))
                .isEqualTo("학습자");
        assertThat(ReflectionTestUtils.getField(foundUser, "role"))
                .isEqualTo(UserRole.USER);
        assertThat(ReflectionTestUtils.getField(foundUser, "status"))
                .isEqualTo(UserStatus.ACTIVE);
        assertThat(foundUser.getCreatedAt()).isNotNull();
        assertThat(foundUser.getUpdatedAt()).isNotNull();
    }

    @Test
    void 같은_이메일은_중복_저장할_수_없다() {
        userRepository.saveAndFlush(
                createUser(UUID.randomUUID(), "duplicate@example.com")
        );

        User duplicateUser = createUser(
                UUID.randomUUID(),
                "duplicate@example.com"
        );

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicateUser))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private User createUser(UUID id, String email) {
        User user = new User();

        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "email", email);
        ReflectionTestUtils.setField(
                user,
                "passwordHash",
                "{bcrypt}encoded-password"
        );
        ReflectionTestUtils.setField(user, "displayName", "학습자");
        ReflectionTestUtils.setField(user, "role", UserRole.USER);
        ReflectionTestUtils.setField(user, "status", UserStatus.ACTIVE);

        return user;
    }
}
