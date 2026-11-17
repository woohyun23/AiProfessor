package com.aiprofessor.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiprofessor.backend.TestcontainersConfiguration;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.support.config.JpaConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaConfig.class, MyProfileService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MyProfileServiceTest {

    private static final String PASSWORD_HASH = "{bcrypt}test-password-hash";

    @Autowired
    private MyProfileService myProfileService;

    @Autowired
    private UserRepository userRepository;

    private final List<UUID> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        userRepository.deleteAllById(userIds);
    }

    @Test
    void 표시_이름_변경을_커밋하고_본인_계정의_다른_필드와_타인_계정을_유지한다() {
        User requester = saveUser("기존 이름");
        User otherUser = saveUser("다른 사용자");

        myProfileService.updateMyProfile(requester.getId(), "새 이름");

        assertThat(myProfileService.getMyProfile(requester.getId()).displayName())
                .isEqualTo("새 이름");
        assertThat(myProfileService.getMyProfile(otherUser.getId()).displayName())
                .isEqualTo("다른 사용자");

        User saved = userRepository.findById(requester.getId()).orElseThrow();
        assertThat(saved.getPasswordHash()).isEqualTo(PASSWORD_HASH);
        assertThat(saved.getEmail()).isEqualTo(requester.getEmail());
    }

    private User saveUser(String displayName) {
        User user = userRepository.saveAndFlush(User.create(
                UUID.randomUUID() + "@example.com", PASSWORD_HASH, displayName
        ));
        userIds.add(user.getId());
        return user;
    }
}
