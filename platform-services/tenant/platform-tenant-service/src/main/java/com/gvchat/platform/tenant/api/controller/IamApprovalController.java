package com.gvchat.platform.tenant.api.controller;

import com.gvchat.platform.tenant.application.IamApprovalApplicationService;
import com.gvchat.platform.tenant.application.IamApprovalApplicationService.IamApprovalSubmitCommand;
import com.gvchat.platform.tenant.domain.approval.IamApproval;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IAM 高风险动作在线复核端点（二次复核）：提交 → PENDING，复核 → APPROVED/REJECTED，提交按 idempotencyKey 幂等。
 */
@RestController
@RequestMapping("/admin/iam/approvals")
public class IamApprovalController {

    private final IamApprovalApplicationService service;

    public IamApprovalController(IamApprovalApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public IamApproval submit(@RequestBody SubmitRequest req) {
        return service.submit(new IamApprovalSubmitCommand(req.tenantId(), req.actionType(), req.resourceType(),
                req.resourceId(), req.operatorId(), req.detailJson(), req.idempotencyKey()));
    }

    @PostMapping("/{id}/approve")
    public IamApproval approve(@PathVariable Long id, @RequestBody ReviewRequest req) {
        return service.approve(id, req.approverId(), req.comment());
    }

    @PostMapping("/{id}/reject")
    public IamApproval reject(@PathVariable Long id, @RequestBody ReviewRequest req) {
        return service.reject(id, req.approverId(), req.comment());
    }

    @GetMapping("/{id}")
    public IamApproval get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping
    public List<IamApproval> list(@RequestParam(required = false) String actionType,
                                  @RequestParam(required = false) String status) {
        return service.list(actionType, status);
    }

    public record SubmitRequest(Long tenantId, String actionType, String resourceType, String resourceId,
                                Long operatorId, String detailJson, String idempotencyKey) {
    }

    public record ReviewRequest(Long approverId, String comment) {
    }
}
