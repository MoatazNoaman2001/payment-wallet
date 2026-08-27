package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.statement.StatementFilter;
import com.moataz.paymentwallet.statement.StatementLine;
import com.moataz.paymentwallet.statement.StatementService;
import com.moataz.paymentwallet.transfer.LedgerDirection;
import com.moataz.paymentwallet.transfer.TransferType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Controller
@RequiredArgsConstructor
public class WebStatementController {

    private final StatementService statementService;
    private final AccountService accountService;

    @PreAuthorize("@ownership.canServiceAccount(#accountNumber, authentication)")
    @GetMapping("/accounts/{accountNumber}/statement")
    public String statement(
            @PathVariable String accountNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TransferType type,
            @RequestParam(required = false) LedgerDirection direction,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @PageableDefault(size = 15, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Model model) {

        OffsetDateTime fromInstant = from == null ? null : from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime toInstant = to == null ? null : to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        StatementFilter filter =
                new StatementFilter(fromInstant, toInstant, type, direction, minAmount, maxAmount);
        Page<StatementLine> page = statementService.statement(accountNumber, filter, pageable);

        model.addAttribute("account", accountService.findByNumber(accountNumber));
        model.addAttribute("page", page);
        model.addAttribute("accountNumber", accountNumber);
        model.addAttribute("types", TransferType.values());
        model.addAttribute("directions", LedgerDirection.values());
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("type", type);
        model.addAttribute("direction", direction);
        model.addAttribute("minAmount", minAmount);
        model.addAttribute("maxAmount", maxAmount);
        model.addAttribute("filtered",
                from != null || to != null || type != null || direction != null
                        || minAmount != null || maxAmount != null);
        model.addAttribute("prevUrl", pageUrl(accountNumber, page.getNumber() - 1, page.getSize(),
                from, to, type, direction, minAmount, maxAmount));
        model.addAttribute("nextUrl", pageUrl(accountNumber, page.getNumber() + 1, page.getSize(),
                from, to, type, direction, minAmount, maxAmount));
        return "statement";
    }

    private String pageUrl(String accountNumber, int page, int size,
                           LocalDate from, LocalDate to, TransferType type, LedgerDirection direction,
                           BigDecimal minAmount, BigDecimal maxAmount) {
        UriComponentsBuilder url = UriComponentsBuilder
                .fromPath("/accounts/{n}/statement")
                .queryParam("page", page)
                .queryParam("size", size);
        addIfPresent(url, "from", from);
        addIfPresent(url, "to", to);
        addIfPresent(url, "type", type);
        addIfPresent(url, "direction", direction);
        addIfPresent(url, "minAmount", minAmount);
        addIfPresent(url, "maxAmount", maxAmount);
        return url.buildAndExpand(accountNumber).toUriString();
    }

    private void addIfPresent(UriComponentsBuilder url, String name, Object value) {
        if (value != null) {
            url.queryParam(name, value);
        }
    }
}
