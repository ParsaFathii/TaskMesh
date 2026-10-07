package com.taskmesh.controller;

import com.taskmesh.controller.dto.LoginRequest;
import com.taskmesh.controller.dto.LoginResponse;
import com.taskmesh.controller.dto.UserResponse;
import com.taskmesh.security.AuthenticatedUser;
import com.taskmesh.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints: login (public, rate limited) and current identity.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(UserResponse.from(authService.currentUserById(user.id())));
    }
}
