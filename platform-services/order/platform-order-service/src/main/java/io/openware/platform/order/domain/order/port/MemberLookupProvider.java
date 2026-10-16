package io.openware.platform.order.domain.order.port;

import java.util.Optional;

/**
 * 消费者会员档案的**只读**端口（{@code cst_member}）：签名上下文的 {@code account_id} → 会员 id。
 *
 * <p><b>为什么它住在 order 上下文而不是 customer 上下文</b>：跨上下文依赖由**调用方**拥有
 * —— order 域需要「当前账号是哪个会员」来收紧读路径（{@code /me/orders}、消费者会话的订单详情/账单/加项），
 * 但会员档案的写入与生命周期归 customer 域。端口只声明 order 域**需要什么**，
 * 由 infra 用一个只读查询适配 customer 域的表；customer 域因此不必知道 order 域的存在
 * （同 {@code domain.ktv.port.KtvPricingPlanProvider} 对计价域的取舍）。
 *
 * <p><b>只读</b>：没有任何写方法。会员档案的创建/绑定走 customer 域自己的入口，
 * order 域不会、也不应该在这里创建会员。
 *
 * <p>解析不出会员（账号没有会员档案、账号为空、租户为空）时返回 {@link Optional#empty()}：
 * 这是**读路径的授权判定依据**，调用方据此回 403 {@code PERMISSION_DENIED} 或 404
 * {@code MEMBER_NOT_FOUND}，绝不能把它降级成「放行」。
 */
public interface MemberLookupProvider {

    /**
     * 按「租户 + 认证账号」取会员 id。
     *
     * <p>租户条件是语义的一部分：{@code account_id} 在租户之间不保证唯一，
     * 不按租户过滤会把别的租户的会员当成当前账号（进而放行/拒绝错误的订单归属）。
     *
     * @param tenantId  租户（{@code null} = 无法定位，返回空）
     * @param accountId 认证账号（{@code null} = 无法定位，返回空）
     */
    Optional<Long> findMemberId(Long tenantId, Long accountId);
}
