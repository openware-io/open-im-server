package com.gvchat.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 预约持久化对象（SaaS 侧 ord_reservation，SAAS_PLATFORM_04 §6.3）。
 * 与 im-order-service 旧 ord_reservation（IM 库）同名不同库，字段已切换为多租户归属。
 *
 * <p>预约对象 = **房型**（{@link #roomTypeId}，见 docs/renovation/KTV_RESERVATION_ROOM_TYPE.md §2）：
 * 创建预约时只写房型，具体包厢由「到店分配包厢」写入 {@link #resourceId}（**分配包厢不改变状态**：
 * 锁房 ≠ 客人到店，到店事实只由「到店登记/到店开台」经 {@link #arrivedAt} 记录）。三种组合：
 * <ul>
 *   <li>{@code room_type_id NULL + resource_id NOT NULL}：历史预约（仅包厢驱动，读取回退展示旧包厢，开台继续用它）；</li>
 *   <li>{@code room_type_id NOT NULL + resource_id NULL}：新预约（房型驱动，待到店分配）；</li>
 *   <li>{@code room_type_id NOT NULL + resource_id NOT NULL}：新预约且已分配到具体包厢。</li>
 * </ul>
 */
@Getter
@Setter
@TableName("ord_reservation")
public class ReservationPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private String reservationNo;
    private String idempotencyKey;
    private Long customerId;
    private String businessType;
    /** 到店分配后的实际包厢（res_resource.id）；房型驱动的新预约在分配前为 null。 */
    private Long resourceId;
    /** 预约房型（res_room_type.id，同门店）；历史预约（只有 resource_id）为 null。 */
    private Long roomTypeId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer partySize;
    private String contact;
    private String status;
    private Long orderId;
    /**
     * 实际到店时间（门店营业本地时间）：由「到店登记」或「到店开台（隐含到店）」写入，未到店为 null。
     * 与 {@link #startAt}（客户预约的时段，计划时间）语义不同，后台预约列表两者分列展示。
     * 列由 V25__ord_reservation_arrived_at.sql 新增。
     */
    private LocalDateTime arrivedAt;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;

    /**
     * 非持久化：预约房型的编码/名称（读路径按 room_type_id 关联资源域房型字典回填）。
     * 预约页与后台列表都要显示「包厢类型」，避免每个前端各自查一次资源域。
     */
    @TableField(exist = false)
    private String roomTypeCode;
    @TableField(exist = false)
    private String roomTypeName;
    /**
     * 非持久化：实际包厢名称（读路径按 resource_id 回填）。
     * 历史预约（无 room_type_id）只靠它展示旧包厢，房型驱动的新预约在分配前为 null。
     */
    @TableField(exist = false)
    private String resourceName;
}