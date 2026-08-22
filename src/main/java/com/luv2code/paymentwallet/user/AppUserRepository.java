package com.luv2code.paymentwallet.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByPublicId(UUID publicId);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);
}
