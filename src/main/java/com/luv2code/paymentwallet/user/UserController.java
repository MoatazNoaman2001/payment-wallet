package com.luv2code.paymentwallet.user;

import com.luv2code.paymentwallet.user.dto.RegisterUserRequest;
import com.luv2code.paymentwallet.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Thin by design: validate, delegate, map the result to an HTTP status.
 * No business rules, no @Transactional, no entities crossing this boundary.
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Registration and account holder lifecycle")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** @Valid is what activates Bean Validation. Drop it and every constraint is ignored. */
    @Operation(summary = "Register a new user",
               description = "Creates a PENDING user with ROLE_CUSTOMER. Email and phone must be unique.")
    @PostMapping
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterUserRequest request,
                                                 UriComponentsBuilder uriBuilder) {
        UserResponse created = userService.register(request);
        URI location = uriBuilder.path("/api/users/{id}")
                                 .buildAndExpand(created.publicId())
                                 .toUri();
        // 201 + Location header is the correct answer for "I created a resource".
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{publicId}")
    public UserResponse getOne(@PathVariable UUID publicId) {
        return userService.findByPublicId(publicId);
    }

    @PostMapping("/{publicId}/activation")
    @ResponseStatus(HttpStatus.OK)
    public UserResponse activate(@PathVariable UUID publicId) {
        return userService.activate(publicId);
    }
}
