package io.openware.common.audit.infra.persistence.mapper;

import io.openware.common.audit.infra.persistence.row.OperatorNameRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 操作人账号只读查询：审计列表/详情需要把 {@code operator_id} 显示成操作人姓名。
 *
 * <p>关联口径：审计表里的 {@code operator_id} 就是 SaaS 后台账号的
 * {@code saa_admin_account.platform_account_id}（平台/租户运营账号，租户签名上下文里的
 * {@code account_id} 与之一致）。审计表已迁到独立 schema {@code open_audit}，因此这里**必须显式限定 schema**。
 *
 * <p>为什么是查询期补全而不是写入期冗余：审计表是高频写入的大表，写入路径上再加一次账号查询会把
 * 上报成本翻倍；而姓名只影响展示。记录里**已经存下的**姓名/账号优先（那是动作发生当时的快照），
 * 只有缺失时才用这里的当前值补——改名不会追改历史记录。
 */
@Mapper
public interface OperatorNameMapper {

    /** 按审计表的 operator_id 批量取姓名与登录名。 */
    @Select("""
        <script>
        SELECT platform_account_id AS operator_id, username AS username, display_name AS display_name
        FROM open_saas.saa_admin_account
        WHERE platform_account_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<OperatorNameRow> selectByOperatorIds(@Param("ids") List<Long> ids);

    /**
     * 关键字 → 候选操作人 ID：姓名或登录名模糊匹配。
     *
     * <p>用途：审计表里 {@code operator_name} 可能为空，只按审计表字段匹配会让「按姓名搜索」查不到记录。
     * 因此先把关键字在账号表里解析成候选 ID，再并到查询条件上（走 {@code operator_id} 索引）。
     * 上限 200 条：运营排查用的关键字不会命中更多账号，超限属于输入过宽，宁可少匹配也不做全表扫描。
     */
    @Select("""
        SELECT platform_account_id FROM open_saas.saa_admin_account
        WHERE platform_account_id IS NOT NULL
          AND (display_name LIKE CONCAT('%', #{keyword}, '%') OR username LIKE CONCAT('%', #{keyword}, '%'))
        ORDER BY platform_account_id
        LIMIT 200
        """)
    List<Long> selectOperatorIdsByKeyword(@Param("keyword") String keyword);
}
