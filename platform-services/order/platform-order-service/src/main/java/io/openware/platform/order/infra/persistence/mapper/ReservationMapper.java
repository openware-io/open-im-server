package io.openware.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.order.infra.persistence.po.ReservationPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReservationMapper extends BaseMapper<ReservationPo> {

    /**
     * 「到店时间已过、仍未到店」的预约：超时未到店任务用（{@code KtvReservationTimeoutService}）。
     *
     * <p>判定口径：{@code status IN ('PENDING','CONFIRMED')}（到店前的两个状态）且
     * {@code start_at} 加宽限期后仍早于 {@code cutoff}。已到店/已开台/已取消/已未到店都不在其中。
     *
     * <p>跨租户一次扫出（与 {@code KtvSessionMapper#selectOpenSessions()} 同范式）：每行自带 tenantId，
     * 逐行设上下文再写。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM ord_reservation WHERE status IN ('PENDING', 'CONFIRMED') "
            + "AND start_at IS NOT NULL AND start_at < #{cutoff}")
    List<ReservationPo> selectNotArrivedOverdue(@Param("cutoff") LocalDateTime cutoff);
}
