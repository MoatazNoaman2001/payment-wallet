package com.moataz.paymentwallet.user;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @EntityGraph(attributePaths = "roles")
    Optional<AppUser> findByPublicId(UUID publicId);

    /** Named explicitly: "EmailWithRoles" is not a derivable property path. */
    @EntityGraph(attributePaths = "roles")
    @Query("select u from AppUser u where u.email = :email")
    Optional<AppUser> findByEmailWithRoles(String email);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);
}
