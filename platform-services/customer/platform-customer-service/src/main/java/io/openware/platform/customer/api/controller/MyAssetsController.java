package io.openware.platform.customer.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.api.dto.MemberPointsView;
import io.openware.platform.customer.api.dto.WalletAccountView;
import io.openware.platform.customer.api.dto.WalletLedgerPageView;
import io.openware.platform.customer.application.MemberApplicationService;
import io.openware.platform.customer.application.MemberDisplay;
import io.openware.platform.customer.application.PointApplicationService;
import io.openware.platform.customer.application.WalletApplicationService;
import io.openware.platform.customer.application.WalletTokenDisplayService;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository.LedgerPage;
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
  public WalletAccountView wallet() {
    MemberDisplay member = currentMember();
    // 币种不再硬编码 CNY：取当时租户币种（缺省 USD，16_CURRENCY_CONVENTIONS §1/§2）。
    // 储值账户懒初始化：没有账户即余额 0（零额只读视图），不因为访问资产页就落库建账户。
    return walletTokenDisplayService.decorate(
        WalletAccountView.from(walletService.balance(member.member().id())));
  }

  @GetMapping("/wallet/ledger")
  public WalletLedgerPageView walletLedger(@RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "20") long pageSize) {
    TenantContext context = TenantContextHolder.get();
    LedgerPage ledger = context == null || context.storeId() == null
        ? walletService.ledger(currentMember().member().id(), page, pageSize)
        : walletService.ledger(currentMember().member().id(), context.storeId(), page, pageSize);
    return walletTokenDisplayService.decorate(ledger, page, pageSize);
  }

  @GetMapping("/points")
  public MemberPointsView points(@RequestParam(defaultValue = "1") long page,
                                 @RequestParam(defaultValue = "20") long pageSize) {
    MemberDisplay member = currentMember();
    pointService.ensureAccount(member.member().id());
    TenantContext context = TenantContextHolder.get();
    PointApplicationService.MemberPointsData data = context == null || context.storeId() == null
        ? pointService.view(member.member().id(), page, pageSize)
        : pointService.view(member.member().id(), context.storeId(), page, pageSize);
    return MemberPointsView.from(data.account(), data.ledger(), data.page(), data.pageSize());
  }

  /**
   * 当前会话账号对应的客户档案（领域档案 + 展示值）。
   *
   * <p>三个端点共用它，因此返回类型一次性定下来：{@code MemberDisplay} 而不是任何持久化对象
   * ——控制器不再依赖 {@code infra.persistence.po}（见 §分层门禁 service-api-boundaries）。
   */
  private MemberDisplay currentMember() {
    TenantContext context = TenantContextHolder.get();
    if (context == null || context.tenantId() <= 0) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
    }
    if (context.accountId() <= 0) {
      throw new ApiException(401, "ACCOUNT_REQUIRED", "缺少已认证账号");
    }
    return context.storeId() == null
        ? memberService.getOrCreateByAccount(context.accountId())
        : memberService.getOrCreateByAccount(context.storeId(), context.accountId());
  }
}
