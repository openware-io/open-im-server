package com.gvchat.platform.customer.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.customer.infra.persistence.po.CstMemberNameTokenPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 客户姓名/客户号盲索引 token（{@code cst_member_name_token}）。
 *
 * <p>两类方法，租户口径不同：
 * <ul>
 *   <li><b>请求路径</b>（{@link #selectMemberIdsByAllTokens}）：不忽略租户拦截器——除了显式传入的
 *       {@code tenantId}，{@code TenantLineInnerInterceptor} 还会再兜一层，缺上下文直接抛
 *       {@code IllegalStateException("租户上下文缺失")}，不会静默跨租户；</li>
 *   <li><b>存量回填路径</b>（{@link #selectTenantIdsWithMembers}、{@link #selectMemberIdsMissingTokens}、
 *       {@link #countMembersMissingTokens}）：定时任务线程没有租户上下文，必须
 *       {@code @InterceptorIgnore(tenantLine = "true")} 并**显式传 tenantId**（同仓
 *       {@code InternalTenantConfigMapper} / {@code KtvSessionMapper#selectOpenSessions} 的写法）。
 *       调用方在逐条**写**之前必须 {@code TenantContextHolder.set(...)} 该客户的租户上下文：
 *       不设上下文会被租户拦截器整批拒绝（本仓踩过的坑，见
 *       {@code KtvSessionApplicationService#refreshOpenRoomFees}）。</li>
 * </ul>
 */
@Mapper
public interface MemberNameTokenMapper extends BaseMapper<CstMemberNameTokenPo> {

    /**
     * 关键词 token 全部命中的候选 member_id（包含语义）。
     *
     * <p>命中判定用 {@code HAVING COUNT(DISTINCT token) = tokenCount}：关键词切出 n 个 token 时，
     * 只有「n 个 token 全都在该客户名下」才会进候选集；走 {@code idx_cst_member_name_token_token}
     * 索引做 IN 过滤，不做全表扫描。返回结果按 member_id 升序，便于上层拼 {@code id IN (...)} 与分页
     * （分页与总数都由同一条 WHERE 决定，不会出现「总数与过滤不一致」）。
     *
     * @param tenantId   租户（显式 + 拦截器双重过滤）
     * @param tokens     关键词 token（调用方保证非空）
     * @param tokenCount token 个数（= tokens.size()，要求全部命中）
     */
    @Select("""
        <script>
        SELECT t.member_id
          FROM cst_member_name_token t
         WHERE t.tenant_id = #{tenantId}
           AND t.token IN
           <foreach collection="tokens" item="token" open="(" separator="," close=")">#{token}</foreach>
         GROUP BY t.member_id
        HAVING COUNT(DISTINCT t.token) = #{tokenCount}
         ORDER BY t.member_id
        </script>
        """)
    List<Long> selectMemberIdsByAllTokens(@Param("tenantId") long tenantId,
                                          @Param("tokens") Collection<String> tokens,
                                          @Param("tokenCount") int tokenCount);

    /** 存在客户档案的租户列表（跨租户，显式无上下文调用；回填任务用它逐租户处理）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT DISTINCT tenant_id FROM cst_member ORDER BY tenant_id")
    List<Long> selectTenantIdsWithMembers();

    /**
     * 指定租户下**没有 token 行**的客户 id（按 id 升序、游标分批）。
     *
     * <p>「没有 token 行」= 从未回填或上次回填补偿删除过 → 本方法天然幂等、可重入：
     * 已经建好索引的客户不会再被选中，失败中断后重新调用即可继续。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT m.id
          FROM cst_member m
         WHERE m.tenant_id = #{tenantId}
           AND m.id > #{afterId}
           AND NOT EXISTS (SELECT 1 FROM cst_member_name_token t
                            WHERE t.tenant_id = m.tenant_id AND t.member_id = m.id)
         ORDER BY m.id
         LIMIT #{limit}
        """)
    List<Long> selectMemberIdsMissingTokens(@Param("tenantId") long tenantId,
                                            @Param("afterId") long afterId,
                                            @Param("limit") int limit);

    /** 指定租户下仍缺 token 的客户数（回填结束后报告 remaining，便于人工确认是否可以关掉任务）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT COUNT(*)
          FROM cst_member m
         WHERE m.tenant_id = #{tenantId}
           AND NOT EXISTS (SELECT 1 FROM cst_member_name_token t
                            WHERE t.tenant_id = m.tenant_id AND t.member_id = m.id)
        """)
    long countMembersMissingTokens(@Param("tenantId") long tenantId);
}
