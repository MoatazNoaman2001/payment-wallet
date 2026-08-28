package com.moataz.paymentwallet.user;

import com.moataz.paymentwallet.common.error.DuplicateResourceException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.user.dto.CounterRegistrationRequest;
import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import com.moataz.paymentwallet.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {
    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final KycService kycService;

    @Transactional
    public UserResponse register(RegisterUserRequest request) {
        return register(request, null);
    }

    @Transactional
    public UserResponse register(RegisterUserRequest request, UUID registrarPublicId) {
        String email = normaliseEmail(request.email());
        String phone = request.phone().trim();

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("Email already registered: " + email);
        }
        if (userRepository.existsByPhone(phone)) {
            throw new DuplicateResourceException("Phone already registered: " + phone);
        }

        Role customer = roleRepository.findByName(Role.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed data missing: " + Role.CUSTOMER + " — did V2 run?"));

        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPhone(phone);
        user.setFullName(request.fullName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(UserStatus.PENDING);
        user.addRole(customer);
        if (registrarPublicId != null) {
            user.setRegisteredBy(userRepository.findByPublicId(registrarPublicId)
                    .orElseThrow(() -> new NotFoundException("No user with id " + registrarPublicId)));
        }

        AppUser saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    /**
     * A walk-in customer: the employee keys in the identity details they just checked, so the
     * registration and the KYC submission are one act and share one transaction.
     */
    @Transactional
    public UserResponse registerAtCounter(CounterRegistrationRequest request, UUID actorPublicId) {
        UserResponse created = register(request.user(), actorPublicId);
        kycService.submit(created.publicId(), request.kyc());
        return created;
    }

    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public Page<com.moataz.paymentwallet.user.dto.UserRow> list(Pageable pageable) {
        return userRepository.findUserRows(pageable);
    }

    @Transactional(readOnly = true)
    public UserResponse findByPublicId(UUID publicId) {
        AppUser user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("No user with id " + publicId));
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public String registrarNameOf(UUID publicId) {
        return userRepository.findRegistrarName(publicId).orElse(null);
    }
}
