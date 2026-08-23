package com.moataz.paymentwallet.user;

import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import com.moataz.paymentwallet.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Registration and account holder lifecycle")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "Register a new user",
               description = "Creates a PENDING user with ROLE_CUSTOMER. Email and phone must be unique.")
    @PostMapping
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterUserRequest request,
                                                 UriComponentsBuilder uriBuilder) {
        UserResponse created = userService.register(request);
        URI location = uriBuilder.path("/api/users/{id}")
                                 .buildAndExpand(created.publicId())
                                 .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PreAuthorize("@ownership.isSelf(#publicId, authentication)")
    @GetMapping("/{publicId}")
    public UserResponse getOne(@PathVariable UUID publicId) {
        return userService.findByPublicId(publicId);
    }

    @Operation(summary = "Activate a user", description = "Administrators only: activation is a KYC decision.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{publicId}/activation")
    @ResponseStatus(HttpStatus.OK)
    public UserResponse activate(@PathVariable UUID publicId) {
        return userService.activate(publicId);
    }
}
