package com.moataz.paymentwallet.user;

import com.moataz.paymentwallet.common.error.DuplicateResourceException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import com.moataz.paymentwallet.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
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

    @Transactional
    public UserResponse register(RegisterUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already registered: " + request.email());
        }
        if (userRepository.existsByPhone(request.phone())) {
            throw new DuplicateResourceException("Phone already registered: " + request.phone());
        }

        Role customer = roleRepository.findByName(Role.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed data missing: " + Role.CUSTOMER + " — did V2 run?"));

        AppUser user = new AppUser();
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setFullName(request.fullName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(UserStatus.PENDING);
        user.addRole(customer);

        AppUser saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public UserResponse findByPublicId(UUID publicId) {
        AppUser user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("No user with id " + publicId));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse activate(UUID publicId) {
        AppUser user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("No user with id " + publicId));
        user.setStatus(UserStatus.ACTIVE);
        return UserResponse.from(user);
    }
}
