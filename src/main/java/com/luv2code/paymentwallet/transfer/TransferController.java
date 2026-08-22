package com.luv2code.paymentwallet.transfer;

import com.luv2code.paymentwallet.transfer.dto.TagsRequest;
import com.luv2code.paymentwallet.transfer.dto.TransferRequest;
import com.luv2code.paymentwallet.transfer.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
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

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @Operation(summary = "Execute a transfer",
               description = "Requires an Idempotency-Key header. Retrying with the same key "
                           + "returns the original transfer instead of moving money again.")
    @PostMapping
    public ResponseEntity<TransferResponse> execute(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            UriComponentsBuilder uriBuilder) {

        TransferResponse response = transferService.execute(request, idempotencyKey);
        URI location = uriBuilder.path("/api/transfers/{reference}")
                                 .buildAndExpand(response.reference())
                                 .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{reference}")
    public TransferResponse getOne(@PathVariable String reference) {
        return transferService.findByReference(reference);
    }

    @Operation(summary = "Replace a transfer's tags",
               description = "Tag names must exist in the tag table (seeded by V2).")
    @PutMapping("/{reference}/tags")
    public List<String> replaceTags(@PathVariable String reference,
                                    @Valid @RequestBody TagsRequest request) {
        return transferService.replaceTags(reference, request.tags());
    }
}
