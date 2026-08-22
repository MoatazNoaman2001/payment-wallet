package com.luv2code.paymentwallet.transfer;

import com.luv2code.paymentwallet.auth.CurrentUser;
import com.luv2code.paymentwallet.transfer.dto.ReversalRequest;
import com.luv2code.paymentwallet.transfer.dto.TagsRequest;
import com.luv2code.paymentwallet.transfer.dto.TransferRequest;
import com.luv2code.paymentwallet.transfer.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/transfers")
@Tag(name = "Transfers", description = "The transfer engine: idempotent, double-entry, locked")
@Validated
public class TransferController {

    private final TransferService transferService;
    private final ReversalService reversalService;
    private final CurrentUser currentUser;

    public TransferController(TransferService transferService, ReversalService reversalService,
                              CurrentUser currentUser) {
        this.transferService = transferService;
        this.reversalService = reversalService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Execute a transfer",
               description = "Requires an Idempotency-Key header. Retrying with the same key "
                           + "returns the original transfer instead of moving money again.")
    @PreAuthorize("@ownership.ownsAccount(#request.sourceAccountNumber(), authentication)")
    @PostMapping
    public ResponseEntity<TransferResponse> execute(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            UriComponentsBuilder uriBuilder) {

        TransferResponse response = transferService.execute(request, idempotencyKey,
                                                            currentUser.publicId());
        URI location = uriBuilder.path("/api/transfers/{reference}")
                                 .buildAndExpand(response.reference())
                                 .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PreAuthorize("@ownership.canSeeTransfer(#reference, authentication)")
    @GetMapping("/{reference}")
    public TransferResponse getOne(@PathVariable String reference) {
        return transferService.findByReference(reference);
    }

    @Operation(summary = "Reverse a transfer",
               description = "Writes a compensating REVERSAL transfer in the opposite direction "
                           + "and marks the original REVERSED. Nothing is deleted. Idempotent: "
                           + "asking twice returns the reversal that already exists. Admin only.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{reference}/reversal")
    public ResponseEntity<TransferResponse> reverse(@PathVariable String reference,
                                                    @Valid @RequestBody ReversalRequest request) {
        return ResponseEntity.status(201)
                .body(reversalService.reverse(reference, request.reason(), currentUser.publicId()));
    }

    @Operation(summary = "Replace a transfer's tags",
               description = "Tag names must exist in the tag table (seeded by V2).")
    @PreAuthorize("@ownership.canSeeTransfer(#reference, authentication)")
    @PutMapping("/{reference}/tags")
    public List<String> replaceTags(@PathVariable String reference,
                                    @Valid @RequestBody TagsRequest request) {
        return transferService.replaceTags(reference, request.tags());
    }
}
