package com.moataz.paymentwallet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Only the crypto library is on the classpath — not spring-boot-starter-security —
 * so nothing is locked down yet and there is no login page. That is Phase 4.
 *
 * The point of hashing now: a plaintext password must never reach the database,
 * not even in a practice project. BCrypt salts each hash itself, which is why
 * two users with the same password get different hashes.
 */
@Configuration
public class SecurityBeansConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
