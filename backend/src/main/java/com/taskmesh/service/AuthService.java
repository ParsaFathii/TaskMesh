package com.taskmesh.service;

import com.taskmesh.controller.ApiException;
import com.taskmesh.controller.dto.LoginRequest;
import com.taskmesh.controller.dto.LoginResponse;
import com.taskmesh.controller.dto.UserBrief;
import com.taskmesh.domain.User;
import com.taskmesh.repository.UserRepository;
import com.taskmesh.security.JwtService;
import com.taskmesh.security.LoginRateLimiter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Login with BCrypt verification, JWT issuance, per-username rate limiting and
 * audit trail. Failed logins are audited with the attempted username only —
 * never the password.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       LoginRateLimiter rateLimiter, AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
    }

    public LoginResponse login(LoginRequest request) {
        String username = request.username();
        if (!rateLimiter.tryAcquire(username)) {
            auditService.failure(null, username, "auth.login", "user", null,
                    Map.of("username", username, "reason", "rate_limited"));
            throw new ApiException.RateLimited(
                    "Too many login attempts; wait a minute before retrying");
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditService.failure(null, username, "auth.login", "user", null,
                    Map.of("username", username));
            throw new ApiException.Unauthorized("Invalid username or password");
        }
        String token = jwtService.issueToken(user);
        auditService.success(user.getId(), user.getUsername(), "auth.login", "user",
                user.getId().toString(), Map.of("username", user.getUsername()));
        return new LoginResponse(token, "Bearer", jwtService.tokenValiditySeconds(),
                new UserBrief(user.getId(), user.getUsername(), user.getRole()));
    }

    /** Resolves the current user for GET /api/v1/me. */
    public User currentUserById(java.util.UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException.NotFound("User not found"));
    }
}
