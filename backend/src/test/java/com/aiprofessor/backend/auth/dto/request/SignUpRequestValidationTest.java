package com.aiprofessor.backend.auth.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SignUpRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    void 이모지_한_개인_표시_이름은_거부한다() {
        SignUpRequest request = new SignUpRequest(
                "learner@example.com",
                "password123",
                "😀"
        );

        assertThat(validator.validate(request))
                .extracting(violation ->
                        violation.getPropertyPath().toString()
                )
                .contains("displayName");
    }

    @Test
    void 이모지_여섯_개인_표시_이름은_허용한다() {
        SignUpRequest request = new SignUpRequest(
                "learner@example.com",
                "password123",
                "😀".repeat(6)
        );

        assertThat(validator.validate(request)).isEmpty();
    }
}
