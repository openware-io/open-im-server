package com.gvchat.platform.resource.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.resource.infra.persistence.po.OccupationPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OccupationMapper extends BaseMapper<OccupationPo> {

    /** 锁定同一资源的有效占用行（事务内 FOR UPDATE），用于冲突判定。 */
    @Select("SELECT * FROM res_occupation WHERE tenant_id = #{tenantId} AND resource_id = #{resourceId} " +
            "AND status IN ('HELD','RESERVED','IN_USE') FOR UPDATE")
    List<OccupationPo> selectValidForUpdate(@Param("tenantId") Long tenantId, @Param("resourceId") Long resourceId);

    /** 查询指定租户下所有有效占用（HELD/RESERVED/IN_USE）的资源 ID，用于 C 端可用性过滤。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT resource_id FROM res_occupation WHERE tenant_id = #{tenantId} AND status IN ('HELD','RESERVED','IN_USE')")
    List<Long> selectActiveResourceIds(@Param("tenantId") Long tenantId);

    /** 扫描已过期 HELD 占用（跨租户，跳过租户拦截器），供过期释放定时任务消费。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM res_occupation WHERE status = 'HELD' AND hold_expires_at < #{now} ORDER BY id LIMIT #{limit}")
    List<OccupationPo> selectExpiredHolds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 扫描**业务时段已结束**（end_at 已过）但仍处于有效状态的占用（HELD/RESERVED/IN_USE，跨租户）。
     *
     * <p>与 {@link #selectExpiredHolds} 互补：后者只看 HELD 的 hold_expires_at（预占超时），
     * 这里看 end_at（业务窗口结束）。结台/取消/转台的占用释放失败（资源服务抖动、进程中断）时，
     * 房态会一直显示「使用中」，包厢既不能被预约分配也不能再次开台——由定时任务按 end_at 兜底自愈。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM res_occupation WHERE status IN ('HELD','RESERVED','IN_USE') AND end_at < #{now} " +
            "ORDER BY id LIMIT #{limit}")
    List<OccupationPo> selectEndedOccupations(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 乐观锁释放：状态与 version 匹配才生效，返回受影响行数（0 = 并发冲突或已迁移）。
     *
     * <p>释放必须把占用窗口的 {@code end_at} 收缩到本次释放时刻：开台写入的是 {@code openedAt + 24h}
     * （订单侧冲突判定窗口），释放时若不收缩，结台后的行会一直带着 24 小时窗口，
     * 任何按 {@code start_at → end_at} 读「实际使用时长 / 资源利用率」的读方都会把一次开台算成 24 小时。
     *
     * <p>{@code LEAST(COALESCE(end_at, updatedAt), updatedAt)} 是单向收缩：已经更早的 {@code end_at}
     * （如 {@code releaseEndedOccupations} 兜底释放的、业务时段本就已结束的占用）保持原值，不会被反向拉长；
     * {@code end_at} 为 NULL 时兜底取释放时刻。释放时刻由服务层显式传入（不用 SQL 的 {@code NOW()}，便于测试）。
     *
     * <p>外层 {@code GREATEST(start_at, ...)} 兜住「占用还没开始就被释放」的倒挂：B 端 caller-supplied 的
     * {@code holdExpiresAt} 可以早于业务时段开始（默认 15 分钟），这种未来时段的 HELD 超时被释放时，
     * 收缩结果会落到 {@code start_at} 之前，形成 {@code end_at < start_at} 的负长度区间。取
     * {@code GREATEST(start_at, ...)} 后最坏是零长度区间 [start_at, start_at]，不会倒退。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE res_occupation SET status = 'RELEASED', version = version + 1, " +
            "end_at = GREATEST(start_at, LEAST(COALESCE(end_at, #{updatedAt}), #{updatedAt})), " +
            "updated_at = #{updatedAt} " +
            "WHERE id = #{id} AND status IN ('HELD','RESERVED','IN_USE') AND version = #{version}")
    int releaseWithVersion(@Param("id") Long id, @Param("version") Integer version, @Param("updatedAt") LocalDateTime updatedAt);

    /** 乐观锁取消：HELD/RESERVED 且 version 匹配才生效，返回受影响行数。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE res_occupation SET status = 'CANCELLED', version = version + 1, updated_at = #{updatedAt} " +
            "WHERE id = #{id} AND status IN ('HELD','RESERVED') AND version = #{version}")
    int cancelWithVersion(@Param("id") Long id, @Param("version") Integer version, @Param("updatedAt") LocalDateTime updatedAt);
}
