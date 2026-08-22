package com.luv2code.paymentwallet.user;

import com.luv2code.paymentwallet.common.error.DuplicateResourceException;
import com.luv2code.paymentwallet.common.error.NotFoundException;
import com.luv2code.paymentwallet.user.dto.RegisterUserRequest;
import com.luv2code.paymentwallet.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Transaction boundaries live on the service, not the controller and not the repository.
 *
 * Two things to notice:
 *  - The mapping to UserResponse happens INSIDE the transactional method, while the
 *    persistence context is open. With open-in-view: false, mapping afterwards would
 *    throw LazyInitializationException on getRoles().
 *  - @Transactional works through a proxy. If register() called another @Transactional
 *    method on `this`, the annotation would be ignored entirely — the call never leaves
 *    the object, so it never passes through the proxy.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

//    // Constructor injection, no @Autowired needed: one constructor is enough for Spring.
//    // It also keeps the fields final and makes the class trivially testable with `new`.
//    public UserService(AppUserRepository userRepository,
//                       RoleRepository roleRepository,
//                       PasswordEncoder passwordEncoder) {
//        this.userRepository = userRepository;
//        this.roleRepository = roleRepository;
//        this.passwordEncoder = passwordEncoder;
//    }

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
        user.setStatus(UserStatus.PENDING);      // becomes ACTIVE after KYC
        user.addRole(customer);

        AppUser saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    @Transactional(readOnly = true)   // readOnly lets Hibernate skip dirty checking
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
        // No save() call. The entity is managed, so Hibernate's dirty checking
        // writes the UPDATE at flush time. This is the part that surprises everyone.
        return UserResponse.from(user);
    }
}
