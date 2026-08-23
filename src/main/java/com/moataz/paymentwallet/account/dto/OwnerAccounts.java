package com.moataz.paymentwallet.account.dto;

import java.util.List;
import java.util.UUID;

public record OwnerAccounts(
        UUID ownerPublicId,
        String ownerName,
        String ownerEmail,
        String ownerStatus,
        List<AccountRow> accounts
) {
    public String initial() {
        return ownerName == null || ownerName.isBlank()
                ? "?" : ownerName.substring(0, 1).toUpperCase();
    }
}
