package com.moataz.paymentwallet.user;

import com.moataz.paymentwallet.user.dto.UserRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    @EntityGraph(attributePaths = "roles")
    Optional<AppUser> findByPublicId(UUID publicId);

    @EntityGraph(attributePaths = "roles")
    @Query("select u from AppUser u where u.email = :email")
    Optional<AppUser> findByEmailWithRoles(String email);

    @Query(value = """
           select new com.moataz.paymentwallet.user.dto.UserRow(
                  u.publicId, u.fullName, u.email, u.phone, u.status, u.createdAt, count(a))
           from AppUser u
             left join Account a on a.user = u
           group by u.publicId, u.fullName, u.email, u.phone, u.status, u.createdAt
           order by u.createdAt desc
           """,
           countQuery = "select count(u) from AppUser u")
    Page<UserRow> findUserRows(Pageable pageable);

    @Query("select r.fullName from AppUser u join u.registeredBy r where u.publicId = :publicId")
    Optional<String> findRegistrarName(UUID publicId);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);
}
