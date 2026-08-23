package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.reliability.OutboxPublisher;
import com.moataz.paymentwallet.reliability.ReconciliationService;
import com.moataz.paymentwallet.transfer.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class WebAdminController {

    private final ReconciliationService reconciliationService;
    private final OutboxPublisher outboxPublisher;
    private final OutboxEventRepository outboxEventRepository;

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("drift", reconciliationService.findDrift());
        model.addAttribute("pending", outboxEventRepository.countByPublishedAtIsNull());
        return "admin";
    }

    @PostMapping("/admin/outbox/publish")
    public String publish() {
        outboxPublisher.publishBatch(100);
        return "redirect:/admin?published";
    }
}
