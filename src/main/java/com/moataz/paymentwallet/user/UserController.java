package com.moataz.paymentwallet.user;

import com.moataz.paymentwallet.auth.CurrentUser;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.user.dto.CounterRegistrationRequest;
import com.moataz.paymentwallet.user.dto.KycProfileResponse;
import com.moataz.paymentwallet.user.dto.KycReviewRequest;
import com.moataz.paymentwallet.user.dto.KycSubmissionRequest;
import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import com.moataz.paymentwallet.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Registration, identity verification and account holder lifecycle")
public class UserController {
    private final UserService userService;
    private final KycService kycService;
    private final CurrentUser currentUser;

    public UserController(UserService userService, KycService kycService, CurrentUser currentUser) {
        this.userService = userService;
        this.kycService = kycService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Register yourself",
               description = "Public. Creates a PENDING user with ROLE_CUSTOMER. Email and phone "
                           + "must be unique. If a signed-in employee calls this, they are recorded "
                           + "as the registrar.")
    @PostMapping
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterUserRequest request,
                                                 UriComponentsBuilder uriBuilder) {
        UserResponse created = userService.register(request,
                currentUser.publicIdIfPresent().orElse(null));
        URI location = uriBuilder.path("/api/users/{id}")
                                 .buildAndExpand(created.publicId())
                                 .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Register a walk-in customer",
               description = "Staff only. Registers the person and records the identity details "
                           + "the employee checked in one step. Still PENDING until compliance reviews.")
    @PreAuthorize("@ownership.isStaff(authentication)")
    @PostMapping("/counter")
    public ResponseEntity<UserResponse> registerAtCounter(
            @Valid @RequestBody CounterRegistrationRequest request,
            UriComponentsBuilder uriBuilder) {
        UserResponse created = userService.registerAtCounter(request, currentUser.publicId());
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

    @Operation(summary = "Submit identity details",
               description = "The account holder, or an employee acting for them. Creates the KYC "
                           + "profile at BASIC. Details can be corrected until compliance reviews them.")
    @PreAuthorize("@ownership.isSelf(#publicId, authentication)")
    @PutMapping("/{publicId}/kyc")
    public KycProfileResponse submitKyc(@PathVariable UUID publicId,
                                        @Valid @RequestBody KycSubmissionRequest request) {
        return kycService.submit(publicId, request);
    }

    @Operation(summary = "Read identity details",
               description = "The national id comes back masked: the file confirms who was verified, "
                           + "it is not a lookup service for identity numbers.")
    @PreAuthorize("@ownership.isSelf(#publicId, authentication)")
    @GetMapping("/{publicId}/kyc")
    public KycProfileResponse getKyc(@PathVariable UUID publicId) {
        return kycService.find(publicId)
                .orElseThrow(() -> new NotFoundException(
                        "No identity details submitted for " + publicId));
    }

    @Operation(summary = "Approve an identity",
               description = "Compliance (or an administrator). Sets the KYC tier, which sets the "
                           + "daily ceiling, and activates the account holder in the same transaction.")
    @PreAuthorize("@ownership.canApproveIdentity(authentication)")
    @PostMapping("/{publicId}/kyc-review")
    @ResponseStatus(HttpStatus.OK)
    public UserResponse review(@PathVariable UUID publicId,
                               @Valid @RequestBody KycReviewRequest request) {
        return kycService.approve(publicId, request.tier(), request.note(), currentUser.publicId());
    }
}
