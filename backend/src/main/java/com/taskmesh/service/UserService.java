package com.taskmesh.service;

import com.taskmesh.controller.ApiException;
import com.taskmesh.controller.dto.CreateUserRequest;
import com.taskmesh.controller.dto.UserResponse;
import com.taskmesh.domain.User;
import com.taskmesh.domain.UserRole;
import com.taskmesh.repository.UserRepository;
import com.taskmesh.security.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Admin-facing user management (list + create). Passwords are BCrypt-hashed before
 * storage and never appear in audit metadata or responses.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public Page<User> list(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    public UserResponse create(CreateUserRequest request, AuthenticatedUser actor) {
        if (userRepository.existsByUsernameIgnoreCase(request.username())) {
            throw new ApiException.Conflict("Username already taken: " + request.username());
        }
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ApiException.Conflict("Email already in use: " + request.email());
        }
        UserRole role = request.role() == null ? UserRole.USER : request.role();
        User user = new User(request.username(), request.email(),
                passwordEncoder.encode(request.password()), role);
        User saved = userRepository.save(user);
        auditService.success(actor.id(), actor.username(), "user.create", "user",
                saved.getId().toString(), Map.of("username", saved.getUsername(),
                        "role", saved.getRole().name()));
        return UserResponse.from(saved);
    }
}
