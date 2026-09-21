package com.gvchat.platform.customer.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.customer.application.MemberApplicationService;
import com.gvchat.platform.customer.application.MemberPointsView;
import com.gvchat.platform.customer.application.PointApplicationService;
import com.gvchat.platform.customer.application.WalletApplicationService;
import com.gvchat.platform.customer.application.WalletTokenDisplayService;
import com.gvchat.platform.customer.infra.persistence.po.CstMemberPo;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletAccountPo;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer-only asset endpoints. The member is always derived from the verified session account.
 *
 * <p>钱包响应含「代币数量」展示字段（{@code tokenAmount} 纯数字数量 + {@code tokenBrandName} 品牌名），
 * 由 {@link WalletTokenDisplayService} 统一换算；货币字段（availableAmount/currencyCode 等）保持原义不变。
 * 积分响应只给积分「个数」，不带币种、不带代币字段。
 */
@RestController
@RequestMapping("/me")
public class MyAssetsController {
  private final MemberApplicationService memberService;
  private final PointApplicationService pointService;
  private final WalletApplicationService walletService;
  private final WalletTokenDisplayService walletTokenDisplayService;

  public MyAssetsController(MemberApplicationService memberService, PointApplicationService pointService,
                            WalletApplicationService walletService,
                            WalletTokenDisplayService walletTokenDisplayService) {
    this.memberService = memberService;
    this.pointService = pointService;
    this.walletService = walletService;
    this.walletTokenDisplayService = walletTokenDisplayService;
  }

  @GetMapping("/wallet")
  public CstWalletAccountPo wallet() {
    CstMemberPo member = currentMember();
    // 币种不再硬编码 CNY：取当时租户币种（缺省 USD，16_CURRENCY_CONVENTIONS §1/§2）。
    // 储值账户懒初始化：没有账户即余额 0（零额只读视图），不因为访问资产页就落库建账户。
    return walletTokenDisplayService.decorate(walletService.balance(member.getId()));
  }

  @GetMapping("/wallet/ledger")
  public Page<CstWalletLedgerPo> walletLedger(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long pageSize) {
    return walletTokenDisplayService.decorate(walletService.ledger(currentMember().getId(), page, pageSize));
  }

  @GetMapping("/points")
  public MemberPointsView points(@RequestParam(defaultValue = "1") long page,
                                 @RequestParam(defaultValue = "20") long pageSize) {
    CstMemberPo member = currentMember();
    pointService.ensureAccount(member.getId());
    return pointService.view(member.getId(), page, pageSize);
  }

  private CstMemberPo currentMember() {
    TenantContext context = TenantContextHolder.get();
    if (context == null || context.accountId() <= 0) {
      throw new ApiException(401, "ACCOUNT_REQUIRED", "缺少已认证账号");
    }
    return memberService.getOrCreateByAccount(context.accountId());
  }
}
