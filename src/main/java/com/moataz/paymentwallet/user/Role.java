package com.moataz.paymentwallet.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Entity
@Table(name = "role")
@Getter
@Setter
public class Role {
    public static final String CUSTOMER = "ROLE_CUSTOMER";
    public static final String MERCHANT = "ROLE_MERCHANT";
    public static final String TELLER   = "ROLE_TELLER";
    public static final String ADMIN    = "ROLE_ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Role other)) return false;
        return Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "Role{" + name + '}';
    }
}
