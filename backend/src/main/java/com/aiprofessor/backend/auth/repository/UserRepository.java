package com.aiprofessor.backend.auth.repository;

import com.aiprofessor.backend.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
}
