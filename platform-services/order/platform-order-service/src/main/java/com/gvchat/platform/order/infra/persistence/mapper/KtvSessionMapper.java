package com.gvchat.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvSessionPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KtvSessionMapper extends BaseMapper<KtvSessionPo> {

    /**
     * 乐观锁更新：仅当 version 匹配才生效，返回受影响行数（0 = 并发冲突）。
     * 会话创建后不变的列（tenant_id/order_id/reserved_*）不在此更新；
     * occupation_id / 包厢名称快照随开台占用一起写（转台走 {@link #transferRoom}）；
     * party_size（开台人数）与 server_id/server_name（服务人员快照）也随状态流转一起写回。
     */
    @Update("UPDATE ord_ktv_session SET status = #{status}, opened_at = #{openedAt}, closed_at = #{closedAt}, " +
            "billing_unit = #{billingUnit}, billing_start_at = #{billingStartAt}, free_wait_minutes = #{freeWaitMinutes}, " +
            "paused_seconds = #{pausedSeconds}, pause_started_at = #{pauseStartedAt}, overtime_rate = #{overtimeRate}, " +
            "billing_rule_snapshot_json = #{billingRuleSnapshotJson}, occupation_id = #{occupationId}, " +
            "room_name_snapshot = #{roomNameSnapshot}, room_code_snapshot = #{roomCodeSnapshot}, " +
            "party_size = #{partySize}, server_id = #{serverId}, server_name = #{serverName}, " +
            "updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE id = #{id} AND version = #{version}")
    int updateWithVersion(KtvSessionPo po);

    /**
     * 回写服务人员快照：点服务人员（ord_ktv_server_session 创建/开始）时把「谁在服务本包厢」固化到会话，
     * 订单列表与房态看板直接读会话，不再跨域回查服务人员点单表。
     * server_name 为 null（资源服务不可达）时用 COALESCE 保留既有名称，避免一次抖动清空已固化的名字。
     */
    @Update("UPDATE ord_ktv_session SET server_id = #{serverId}, "
            + "server_name = COALESCE(#{serverName}, server_name), updated_at = #{updatedAt} "
            + "WHERE id = #{id}")
    int updateServerSnapshot(@Param("id") Long id, @Param("serverId") Long serverId,
                             @Param("serverName") String serverName, @Param("updatedAt") LocalDateTime updatedAt);

    /** 跨租户鉴别：忽略租户拦截，仅按主键读取 tenant_id（区分「不存在」vs「跨租户」）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT tenant_id FROM ord_ktv_session WHERE id = #{id}")
    Long selectTenantIdById(@Param("id") Long id);

    /** 按订单 ID 查会话（转台/作废等按订单命令驱动）。 */
    @Select("SELECT * FROM ord_ktv_session WHERE order_id = #{orderId} ORDER BY id DESC LIMIT 1")
    KtvSessionPo selectByOrderId(@Param("orderId") Long orderId);

    /** 转台：更新包厢与占位/快照（计时继承不重置），乐观锁 version 校验。 */
    @Update("UPDATE ord_ktv_session SET room_resource_id = #{roomResourceId}, occupation_id = #{occupationId}, " +
            "room_name_snapshot = #{roomNameSnapshot}, room_code_snapshot = #{roomCodeSnapshot}, " +
            "updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE id = #{id} AND version = #{version}")
    int transferRoom(KtvSessionPo po);

    /**
     * 开台中（OPEN/PAUSED）会话：房费刷新任务用。
     * 定时任务没有租户上下文，必须忽略租户行过滤（跨租户一次扫出；每行自带 tenantId，不做跨租户写）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM ord_ktv_session WHERE status IN ('OPEN', 'PAUSED')")
    List<KtvSessionPo> selectOpenSessions();

    /**
     * 「已预留但一直没开台」的会话：超时释放任务用（{@code KtvReservationTimeoutService}）。
     *
     * <p>判定口径：{@code status = 'RESERVED'} 且从未开台（{@code opened_at IS NULL}），
     * 参考时间取**预约到店时间**优先、没有则退回**创建时间**（C 端预留 / 后台预约开台两条路都可能不写
     * {@code reserved_start_at}），加宽限期后仍早于 {@code cutoff} 即视为**未到店**。
     *
     * <p>跨租户一次扫出（与 {@link #selectOpenSessions()} 同范式）：每行自带 tenantId，逐行设上下文再写，
     * 绝不跨租户写。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM ord_ktv_session WHERE status = 'RESERVED' AND opened_at IS NULL "
            + "AND COALESCE(reserved_start_at, created_at) < #{cutoff}")
    List<KtvSessionPo> selectReservedOverdue(@Param("cutoff") LocalDateTime cutoff);
}
