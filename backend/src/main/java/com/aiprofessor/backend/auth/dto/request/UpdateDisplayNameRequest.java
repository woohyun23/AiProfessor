package com.aiprofessor.backend.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

public record UpdateDisplayNameRequest(
        @NotBlank(message = "이름은 필수입니다.")
        @CodePointLength(min = 2, max = 10, message = "이름은 2자 이상 10자 이하여야 합니다.")
        String displayName
) {
}
