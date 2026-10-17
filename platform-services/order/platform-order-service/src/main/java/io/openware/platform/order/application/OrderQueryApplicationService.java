package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderQuery;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C 端「我的订单」查询：会员归属解析 + 订单查询。
 *
 * <p>控制器只做响应映射（standards/10 §应用服务规则）；`account_id → cst_member.id`
 * 走 order 域自己的只读端口，跨上下文只读依赖由调用方拥有。
 */
@Service
@RequiredArgsConstructor
public class OrderQueryApplicationService {
  private final MemberLookupProvider memberLookupProvider;
  private final OrderRepository orderRepository;

  /** 当前登录账号，按创建时间倒序。 */
  @Transactional(readOnly = true)
  public List<Order> listMine() {
    long memberId = currentMemberId();
    return orderRepository.list(OrderQuery.newestFirst().withCustomer(memberId)).stream()
        .map(Persisted::aggregate)
        .toList();
  }

  /** 当前登录账号对应的会员 id；解析不出会员时拒绝（不泄露可探测的信息面）。 */
  @Transactional(readOnly = true)
  public long currentMemberId() {
    TenantContext context = TenantContextHolder.get();
    if (context == null) {
      throw new ApiException(401, "ACCOUNT_REQUIRED", "缺少已认证账号");
    }
    Long memberId = memberLookupProvider.findMemberId(context.tenantId(), context.accountId()).orElse(null);
    if (memberId == null) {
      throw new ApiException(404, "MEMBER_NOT_FOUND", "会员档案尚未初始化");
    }
    return memberId;
  }
}
