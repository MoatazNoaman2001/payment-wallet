package com.moataz.paymentwallet.account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;

/**
 * Reference data, seeded by V2__seed_reference_data.sql. The primary key is the
 * natural business key (ISO 4217 code) — no surrogate id needed.
 *
 * @JdbcTypeCode(SqlTypes.CHAR) matters: the column is CHAR(3), and without this
 * Hibernate expects VARCHAR and ddl-auto: validate fails at startup with
 * "found [bpchar], but expecting [varchar(3)]".
 */
@Entity
@Table(name = "currency")
@Getter
@Setter
public class Currency {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "code", length = 3)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "minor_units", nullable = false)
    private Short minorUnits;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Currency other)) return false;
        return Objects.equals(code, other.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return "Currency{" + code + '}';
    }
}
