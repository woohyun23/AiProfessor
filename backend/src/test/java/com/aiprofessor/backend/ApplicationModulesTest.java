package com.aiprofessor.backend;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ApplicationModulesTest {

    @Test
    void 애플리케이션_모듈은_공개된_인터페이스를_통해_의존한다() {
        ApplicationModules.of(BackendApplication.class).verify();
    }
}
