package io.openware.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 单据号每日序号分配行（{@code ord_daily_serial}，生产迁移
 * {@code V27__ord_daily_serial.sql}）。
 *
 * <p>一行 = 一个「租户 + 单据类型 + 营业日」的序号游标；单号格式与口径见
 * {@link io.openware.platform.order.application.DailySerialNumberGenerator}。
 * 本表只服务新建单据：历史 {@code ord_order.order_no} / {@code ord_reservation.reservation_no}
 * 不会被回填、改名或重排。
 */
@Getter
@Setter
@TableName("ord_daily_serial")
public class DailySerialPo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户ID：单号的唯一域，与 {@code uk_ord_order_tenant_no (tenant_id, order_no)} 一致。 */
    private Long tenantId;

    /** 单据类型（{@code ORDER} / {@code RESERVATION}），同时决定单号前缀 O / R。 */
    private String bizType;

    /** 营业日（平台默认 Asia/Shanghai + 04:00 切点），不是自然日。 */
    private LocalDate businessDate;

    /** 该营业日已分配到的序号；0 表示尚无单。 */
    private Long currentSeq;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
