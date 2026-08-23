package com.moataz.paymentwallet.statement;

import com.moataz.paymentwallet.transfer.LedgerEntry;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

final class LedgerEntrySpecifications {
    private LedgerEntrySpecifications() {
    }

    static Specification<LedgerEntry> matching(String accountNumber, StatementFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("account").get("accountNumber"), accountNumber));

            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), filter.to()));
            }
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("transfer").get("type"), filter.type()));
            }
            if (filter.direction() != null) {
                predicates.add(cb.equal(root.get("direction"), filter.direction()));
            }
            if (filter.minAmount() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), filter.minAmount()));
            }
            if (filter.maxAmount() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), filter.maxAmount()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
