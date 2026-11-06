package com.aiprofessor.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.aiprofessor.backend.auth.config.PasswordEncoderConfig;
import com.aiprofessor.backend.auth.domain.EmailNormalizer;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.domain.UserRole;
import com.aiprofessor.backend.auth.domain.UserStatus;
import com.aiprofessor.backend.auth.dto.request.SignUpRequest;
import com.aiprofessor.backend.auth.dto.response.SignUpResponse;
import com.aiprofessor.backend.auth.error.UserErrorCode;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.support.error.ApiException;
import com.aiprofessor.backend.support.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class SignUpServiceTest {

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private SignUpService signUpService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new PasswordEncoderConfig().passwordEncoder();
        signUpService = new SignUpService(
                userRepository,
                passwordEncoder,
                new EmailNormalizer()
        );
    }

    @Test
    void 이메일을_정규화하고_비밀번호를_암호화하여_회원가입한다() {
        SignUpRequest request = new SignUpRequest(
                "  Learner@Example.COM  ",
                "password123",
                "학습자"
        );
        given(userRepository.saveAndFlush(any(User.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        SignUpResponse response = signUpService.signUp(request);

        ArgumentCaptor<User> userCaptor =
                ArgumentCaptor.forClass(User.class);
        verify(userRepository).existsByEmail("learner@example.com");
        verify(userRepository).saveAndFlush(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("learner@example.com");
        assertThat(savedUser.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches(
                request.password(),
                savedUser.getPasswordHash()
        )).isTrue();
        assertThat(savedUser.getDisplayName()).isEqualTo("학습자");
        assertThat(savedUser.getRole()).isEqualTo(UserRole.USER);
        assertThat(savedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.userId()).isEqualTo(savedUser.getId());
        assertThat(response.email()).isEqualTo("learner@example.com");
        assertThat(response.displayName()).isEqualTo("학습자");
    }

    @Test
    void 이미_가입된_이메일이면_회원가입을_거부한다() {
        SignUpRequest request = new SignUpRequest(
                "Duplicate@Example.com",
                "password123",
                "학습자"
        );
        given(userRepository.existsByEmail("duplicate@example.com"))
                .willReturn(true);

        assertThatThrownBy(() -> signUpService.signUp(request))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(UserErrorCode.EMAIL_ALREADY_EXISTS)
                );

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void 동시_가입으로_유니크_제약조건을_위반하면_중복_이메일_오류로_변환한다() {
        SignUpRequest request = new SignUpRequest(
                "race@example.com",
                "password123",
                "학습자"
        );
        given(userRepository.saveAndFlush(any(User.class)))
                .willThrow(
                        new DataIntegrityViolationException("duplicate email")
                );

        assertThatThrownBy(() -> signUpService.signUp(request))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(UserErrorCode.EMAIL_ALREADY_EXISTS)
                );
    }

    @Test
    void 비밀번호가_BCrypt_입력_제한을_넘으면_회원가입을_거부한다() {
        SignUpRequest request = new SignUpRequest(
                "learner@example.com",
                "가".repeat(25),
                "학습자"
        );

        assertThatThrownBy(() -> signUpService.signUp(request))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.INVALID_REQUEST)
                );

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }
}
