package com.moataz.paymentwallet.transfer.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record TagsRequest(
        @NotEmpty(message = "at least one tag is required")
        Set<String> tags
) {
}
