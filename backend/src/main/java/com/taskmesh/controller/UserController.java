package com.taskmesh.controller;

import com.taskmesh.controller.dto.CreateUserRequest;
import com.taskmesh.controller.dto.PageResponse;
import com.taskmesh.controller.dto.UserResponse;
import com.taskmesh.security.AuthenticatedUser;
import com.taskmesh.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Admin-only user management.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        PageRequest pageable = (PageRequest) Pagination.toPage(page, size,
                Sort.by(Sort.Direction.ASC, "username"));
        return PageResponse.of(userService.list(pageable), UserResponse::from);
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                               @Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.create(request, actor);
        URI location = UriComponentsBuilder.fromPath("/api/v1/users/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }
}
