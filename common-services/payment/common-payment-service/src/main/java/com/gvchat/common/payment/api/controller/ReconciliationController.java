package com.gvchat.common.payment.api.controller;

import com.gvchat.common.payment.application.ReconciliationApplicationService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/** 对账摘要（按渠道汇总）。 */
@RestController
@RequestMapping("/admin/reconciliations")
public class ReconciliationController {
    private final ReconciliationApplicationService reconciliationService;

    public ReconciliationController(ReconciliationApplicationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/summary")
    public ReconciliationApplicationService.ReconciliationSummary summary(@RequestParam Long tenantId,
            @RequestParam LocalDateTime from, @RequestParam LocalDateTime to) {
        return reconciliationService.summary(tenantId, from, to);
    }
}
