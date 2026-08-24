package com.moataz.paymentwallet.config;

import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.Role;
import com.moataz.paymentwallet.user.RoleRepository;
import com.moataz.paymentwallet.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Order(1)
@RequiredArgsConstructor
public class StaffAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffAccountInitializer.class);

    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${staff.admin.email:admin@paymentwallet.local}")
    private String adminEmail;

    @Value("${staff.admin.password:admin12345}")
    private String adminPassword;

    @Value("${staff.teller.email:teller@paymentwallet.local}")
    private String tellerEmail;

    @Value("${staff.teller.password:teller12345}")
    private String tellerPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureRoles();
        ensureStaff(adminEmail, "+000000000001", "Demo Administrator", adminPassword, Role.ADMIN);
        ensureStaff(tellerEmail, "+000000000002", "Demo Teller", tellerPassword, Role.TELLER);
    }

    private void ensureRoles() {
        List<String> created = List.of(Role.CUSTOMER, Role.MERCHANT, Role.TELLER, Role.ADMIN).stream()
                .filter(name -> roleRepository.findByName(name).isEmpty())
                .map(this::createRole)
                .toList();
        if (!created.isEmpty()) {
            log.warn("Created missing roles: {}", created);
        }
    }

    private String createRole(String name) {
        Role role = new Role();
        role.setName(name);
        roleRepository.saveAndFlush(role);
        return name;
    }

    private void ensureStaff(String email, String phone, String fullName,
                             String rawPassword, String staffRole) {
        if (userRepository.existsByEmail(email)) {
            return;
        }

        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPhone(phone);
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatus(UserStatus.ACTIVE);
        roleRepository.findByName(staffRole).ifPresent(user::addRole);
        roleRepository.findByName(Role.CUSTOMER).ifPresent(user::addRole);
        userRepository.saveAndFlush(user);

        log.warn("Created missing staff account {} with {}", email, staffRole);
    }
}
