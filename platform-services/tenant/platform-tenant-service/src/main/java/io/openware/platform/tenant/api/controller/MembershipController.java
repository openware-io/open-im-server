package io.openware.platform.tenant.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.platform.tenant.application.MembershipApplicationService;
import io.openware.platform.tenant.domain.membership.Membership;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 租户成员 API：员工/客户身份是租户内授权主体。 */
@RestController
public class MembershipController {

  private final MembershipApplicationService service;

  public MembershipController(MembershipApplicationService service) {
    this.service = service;
  }

  @GetMapping("/admin/memberships")
  public List<MembershipView> list(@RequestParam(required = false) String type,
                                   @RequestParam(required = false) String status) {
    return service.list(type, status).stream().map(MembershipView::of).toList();
  }

  @PostMapping("/admin/memberships")
  public MembershipView create(@RequestBody CreateMembershipRequest request) {
    try {
      return MembershipView.of(service.create(request.accountId(), request.organizationId(),
          request.membershipType(), request.memberNo()));
    } catch (IllegalArgumentException ex) {
      throw new ApiException(400, "MEMBERSHIP_INVALID", ex.getMessage());
    }
  }

  @PostMapping("/admin/memberships/{id}/status")
  public MembershipView changeStatus(@PathVariable Long id, @RequestBody ChangeStatusRequest request) {
    try {
      return MembershipView.of(service.changeStatus(id, request.status()));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      throw new ApiException(400, "MEMBERSHIP_STATUS_INVALID", ex.getMessage());
    }
  }

  @GetMapping("/internal/iam/accounts/{accountId}/memberships")
  public List<MembershipView> byAccount(@PathVariable Long accountId) {
    return service.listByAccount(accountId).stream().map(MembershipView::of).toList();
  }

  @GetMapping("/internal/iam/memberships/{id}")
  public MembershipView get(@PathVariable Long id) {
    return MembershipView.of(service.get(id));
  }

  public record CreateMembershipRequest(Long accountId, Long organizationId,
                                        String membershipType, String memberNo) {
  }

  public record ChangeStatusRequest(String status) {
  }

  public record MembershipView(Long id, Long accountId, Long tenantId, Long organizationId,
                               String membershipType, String memberNo,
                               int authorizationVersion, String status) {
    public static MembershipView of(Membership m) {
      return new MembershipView(m.id(), m.accountId(), m.tenantId(), m.organizationId(),
          m.membershipType(), m.memberNo(), m.authorizationVersion(), m.status());
    }
  }
}
