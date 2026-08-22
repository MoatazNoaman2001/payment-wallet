package com.luv2code.paymentwallet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Switches on @CreatedDate / @LastModifiedDate. Without this annotation those
 * fields stay null and the NOT NULL columns blow up on insert.
 *
 * The custom DateTimeProvider is not optional here: Spring Data's default provider
 * hands back a LocalDateTime, and it refuses to convert that into the OffsetDateTime
 * fields our TIMESTAMPTZ columns need —
 *   "Cannot convert unsupported date type java.time.LocalDateTime to java.time.OffsetDateTime"
 * Producing the timestamp in UTC up front also means the stored instant never depends
 * on the machine's local time zone.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "utcDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider utcDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now(ZoneOffset.UTC));
    }
}
