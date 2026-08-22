package com.luv2code.paymentwallet.account;

import com.luv2code.paymentwallet.user.AppUser;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A wallet. balance is a cached projection of the ledger (Phase 2 keeps it in step);
 * the ledger stays the source of truth.
 *
 * Every @ManyToOne here is explicitly LAZY. JPA's default for @ManyToOne is EAGER,
 * which quietly joins (or re-queries) the parent on every single load — the most
 * common cause of surprise N+1 in a Spring Data app.
 */
@Entity
@Table(name = "account")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, updatable = false, length = 24)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", updatable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "currency_code", updatable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    /**
     * NUMERIC(19,4) -> BigDecimal. Never double: 0.1 + 0.2 != 0.3 in binary floating point,
     * and money that is off by a cent is a bug you cannot argue your way out of.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "daily_limit", precision = 19, scale = 4)
    private BigDecimal dailyLimit;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Version
    private Long version;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Account other)) return false;
        return Objects.equals(accountNumber, other.accountNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountNumber);
    }

    @Override
    public String toString() {
        return "Account{" + accountNumber + ", " + type + ", " + status + '}';
    }
}
