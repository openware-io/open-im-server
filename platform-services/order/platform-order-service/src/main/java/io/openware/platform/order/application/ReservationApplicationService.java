package io.openware.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.order.domain.reservation.model.ReservationStatus;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.client.RoomTypeCatalogClient;
import io.openware.platform.order.infra.client.RoomTypeCatalogClient.RoomTypeView;
import io.openware.platform.order.infra.client.RoomTypeCatalogClient.RoomView;
import io.openware.platform.order.infra.client.TenantBusinessHoursClient;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.mapper.ReservationMapper;
import io.openware.platform.order.infra.persistence.po.KtvSessionPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import io.openware.platform.order.infra.persistence.po.ReservationPo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 预约应用服务（SaaS 新契约写路径，SAAS_PLATFORM_05 §5.3 / SAAS_PLATFORM_04 §6.3）。
 *
 * <p><b>预约对象 = 房型</b>（docs/renovation/KTV_RESERVATION_ROOM_TYPE.md）：一个门店可能几十上百个包厢，
 * 房型却不多，且具体包厢号到店后才由门店分配。所以：
 * <ul>
 *   <li>创建预约只认 {@code roomTypeId}（必填）：校验房型存在/启用/属于该门店、该房型至少有 1 个启用包厢，
 *       并按「同门店 + 同房型 + 时段重叠的未取消预约数 ≥ 该房型启用包厢数」做**超订软保护**；
 *       不再校验「具体包厢是否可用」（那是到店分配与开台的事）。</li>
 *   <li>{@code resourceId} 由「到店分配包厢」（{@link #assignRoom}）写入，开台前必须已分配。</li>
 *   <li>历史预约（只有 resource_id、没有 room_type_id）保持旧语义：列表/详情回退展示旧包厢，
 *       开台继续用既有 resource_id，不参与房型超订统计。</li>
 * </ul>
 *
 * <p>状态机：PENDING → CONFIRMED → ARRIVED → CONVERTED；
 * 异常分支：PENDING/CONFIRMED（到店前）→ CANCELLED；已过预约开始时间仍未到店 → NO_SHOW；
 * 快捷路径：CONFIRMED + 已分配包厢 → CONVERTED（「到店开台」隐含登记到店时间 {@code arrived_at}）。
 *
 * <p><b>锁房与到店分离</b>：{@link #assignRoom} 只写 {@code resource_id}，**不改变状态**——
 * 运营提前指定包厢（备房/排班）不等于客人已到店；到店事实只能由 {@link #arrival}（显式登记）
 * 或 {@link #openTable}（到店并开台，隐含）写入 {@code ord_reservation.arrived_at}。
 *
 * <p>一致性：
 * <ul>
 *   <li>租户上下文：tenant_id 取自 {@link TenantContextHolder}（由 X-Tenant-Context 过滤器注入），
 *       查询/更新由 TenantLineInnerInterceptor 自动追加 tenant_id 过滤，跨租户访问按 404 处理。</li>
 *   <li>乐观锁：写路径通过 version 字段做 WHERE version = 旧值 的乐观更新，冲突抛 RESERVATION_VERSION_CONFLICT。</li>
 *   <li>幂等：create 以 Idempotency-Key 落 idempotency_key 列，唯一键 (tenant_id, idempotency_key) 兜底，
 *       重试返回已有预约。</li>
 *   <li>单号：预约号 {@code R<yyyyMMdd><当日序号>}、开台产生的订单号 {@code O<yyyyMMdd><当日序号>}，
 *       统一由 {@link DailySerialNumberGenerator} 分配（门店营业日 + 租户每日序号 + 失败关闭）；
 *       历史单号（R+UUID / O+毫秒时间戳）原样保留。</li>
 *   <li>房型/包厢数据在资源域：写路径读不到就**失败关闭**（{@code RESOURCE_STATE_UNAVAILABLE}），
 *       读路径只丢展示字段（房型名/包厢名），不推翻预约本身。</li>
 * </ul>
 */
@Slf4j
@Service
public class ReservationApplicationService {

    private final ReservationMapper reservationMapper;
    private final OrderMapper orderMapper;
    private final KtvSessionApplicationService ktvSessionService;
    private final ResourceStateClient resourceStateClient;
    private final RoomTypeCatalogClient roomTypeCatalogClient;
    private final TenantBusinessHoursClient businessHoursClient;
    private final AuditClient auditClient;
    private final DailySerialNumberGenerator dailySerialNumberGenerator;

    public ReservationApplicationService(ReservationMapper reservationMapper,
                                         OrderMapper orderMapper,
                                         KtvSessionApplicationService ktvSessionService,
                                         ResourceStateClient resourceStateClient,
                                         RoomTypeCatalogClient roomTypeCatalogClient,
                                         TenantBusinessHoursClient businessHoursClient,
                                         AuditClient auditClient,
                                         DailySerialNumberGenerator dailySerialNumberGenerator) {
        this.reservationMapper = reservationMapper;
        this.orderMapper = orderMapper;
        this.ktvSessionService = ktvSessionService;
        this.resourceStateClient = resourceStateClient;
        this.roomTypeCatalogClient = roomTypeCatalogClient;
        this.businessHoursClient = businessHoursClient;
        this.auditClient = auditClient;
        this.dailySerialNumberGenerator = dailySerialNumberGenerator;
    }

    /**
     * 创建预约：PENDING，生成 reservation_no，写 ord_reservation；幂等（Idempotency-Key）。
     * startAt/endAt 以带时区偏移的 OffsetDateTime 入参，统一转换为北京时间（UTC+8）后落库，
     * 避免转 UTC 导致前端按本地时钟展示时出现 8 小时偏移。
     *
     * <p>落库的是**门店营业本地墙上时间**（+08:00 的 LocalDateTime，见
     * V19__ord_reservation_time_comment.sql 对 start_at/end_at 的列注释）；读出来直接就是门店本地时间，
     * 调用方不要再做一次时区换算。
     *
     * <p>房型驱动：只写 {@code room_type_id}，{@code resource_id} 留给「到店分配包厢」。
     *
     * <p><b>预约号规则</b>：{@code R<yyyyMMdd><当日序号>}（如 {@code R202609190001}），日期是门店营业日
     * （Asia/Shanghai + 04:00 切点），序号按租户每日从 0001 递增，由 {@link DailySerialNumberGenerator}
     * 在数据库里分配（并发安全；序号服务不可用 → 503 {@code DOC_NO_SEQUENCE_UNAVAILABLE} 失败关闭，
     * 不降级为 UUID/时间戳）。历史预约号（{@code R<UUID 前 20 位>}）原样保留。
     *
     * <p><b>幂等重试不换号</b>：发号在幂等检查**之后**（同 Idempotency-Key 命中已有预约时直接返回，
     * 不再发号）；并发重试那一路即使多发了一个号，返回给调用方的仍是既有预约的号。
     */
    @Transactional
    public ReservationPo create(Long tenantId, String idempotencyKey, CreateReservationCommand cmd) {
        requireTenant(tenantId);
        validateCreate(cmd);
        // 营业时间（统一原则）：到店时间必须落在营业时段内。放在最前面 —— 它是本域就能判定的规则，
        // 不必先远程校验房型；缺省 18:00–次日 05:00，租户/门店可在「KTV 配置 → 营业时间」覆盖。
        requireWithinBusinessHours(cmd);

        // 幂等：同租户同 Idempotency-Key 已创建则直接返回（重试语义）。
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            ReservationPo existing = findByIdempotencyKey(tenantId, idempotencyKey);
            if (existing != null) {
                return existing;
            }
        }

        // 房型校验：存在 + 启用 + 属于该门店（读不到资源域就失败关闭，不静默放行）。
        RoomTypeView roomType = requireBookableRoomType(cmd.storeId(), cmd.roomTypeId());
        long enabledRooms = requireEnabledRooms(cmd.storeId(), roomType);
        requireRoomTypeNotOverbooked(tenantId, cmd, enabledRooms);

        LocalDateTime now = LocalDateTime.now();
        ReservationPo po = new ReservationPo();
        po.setTenantId(tenantId);
        po.setStoreId(cmd.storeId());
        po.setReservationNo(dailySerialNumberGenerator.next(DailySerialNumberGenerator.DocType.RESERVATION,
                tenantId));
        po.setIdempotencyKey(blankToNull(idempotencyKey));
        po.setCustomerId(cmd.customerId());
        po.setBusinessType(cmd.businessType());
        // 预约创建时不写具体包厢：到店分配后再写，避免「预约就锁房」与多端抢房。
        po.setResourceId(null);
        po.setRoomTypeId(cmd.roomTypeId());
        po.setRoomTypeCode(roomType.code());
        po.setRoomTypeName(roomType.name());
        po.setStartAt(toBusinessLocal(cmd.startAt()));
        po.setEndAt(toBusinessLocal(cmd.endAt()));
        po.setPartySize(cmd.partySize() == null ? 1 : cmd.partySize());
        po.setContact(blankToNull(cmd.contact()));
        po.setStatus(ReservationStatus.PENDING.name());
        po.setOrderId(null);
        po.setVersion(0);
        po.setCreatedBy(operatorId());
        po.setCreatedAt(now);
        po.setUpdatedBy(operatorId());
        po.setUpdatedAt(now);

        try {
            reservationMapper.insert(po);
        } catch (DuplicateKeyException e) {
            // 并发重试：唯一键 (tenant_id, idempotency_key) 命中后返回已存在记录。
            ReservationPo existing = findByIdempotencyKey(tenantId, idempotencyKey);
            if (existing != null) {
                return existing;
            }
            throw e;
        }
        return po;
    }

    /**
     * 预约列表（当前租户）：按开门时间倒序，并回填房型名/包厢名（读路径降级，见 {@link #enrichDisplayFields}）。
     *
     * <p><b>时间区间</b>：{@code range} 是按**预约时段开始时间** {@code ord_reservation.start_at} 的闭区间
     * （统一 {@code from}/{@code to} 口径，见 {@link TimeRangeParams}）；为空 = 不筛。
     * 落在 {@code start_at} 列上、不用函数包裹，保持索引可用；排序不变。
     */
    public List<ReservationPo> list(TimeRange range) {
        List<ReservationPo> rows = reservationMapper.selectList(new LambdaQueryWrapper<ReservationPo>()
                .ge(range != null && range.hasFrom(), ReservationPo::getStartAt,
                        range == null ? null : range.fromInclusive())
                .le(range != null && range.hasTo(), ReservationPo::getStartAt,
                        range == null ? null : range.toInclusive())
                .orderByDesc(ReservationPo::getStartAt));
        return enrichDisplayFields(rows);
    }

    /** 预约详情（当前租户；跨租户由租户拦截器过滤为 null，返回 404 语义）。 */
    public ReservationPo get(Long id) {
        return enrichDisplayFields(List.of(require(id))).get(0);
    }

    /**
     * 只读路径的展示字段回填（供其它只读入口复用，如 C 端「我的预约」{@code /me/reservations}）：
     * 按 room_type_id 回填房型名/编码、按 resource_id 回填包厢名；资源域不可达时只丢展示字段。
     */
    public List<ReservationPo> enrichForDisplay(List<ReservationPo> rows) {
        return enrichDisplayFields(rows);
    }

    /** 确认预约：PENDING → CONFIRMED；expectedVersion 用于乐观锁校验。 */
    @Transactional
    public ReservationPo confirm(Long id, Integer expectedVersion) {
        try {
            ReservationPo po = require(id);
            requireStatus(po, ReservationStatus.PENDING, "确认");
            assertVersion(po, expectedVersion);
            ReservationPo confirmed = updateStatus(po, ReservationStatus.CONFIRMED, po.getOrderId());
            // 确认是预约进入可到店状态的唯一入口，此前成功/失败都没有留痕。
            recordReservationAudit("reservation.confirm", "预约确认", confirmed,
                    "{\"reservationNo\":" + jsonText(confirmed.getReservationNo())
                            + ",\"beforeStatus\":\"PENDING\",\"afterStatus\":" + jsonText(confirmed.getStatus()) + "}");
            return confirmed;
        } catch (RuntimeException failure) {
            recordFailure("reservation.confirm", id, failure);
            throw failure;
        }
    }

    /**
     * 到店登记：CONFIRMED → ARRIVED，并落 {@code arrived_at}（真实到店时间）。
     *
     * <p>到店事实的唯一显式入口：分配包厢**不会**改状态，因此这里不再可能被跳过；
     * 运营若要一次完成「客人到了就直接开台」，可直接调 {@link #openTable}（隐含到店）。
     */
    @Transactional
    public ReservationPo arrival(Long id, String operatorNote) {
        try {
            ReservationPo po = require(id);
            requireStatus(po, ReservationStatus.CONFIRMED, "到店");
            // operatorNote 暂无落库字段，仅作接口兼容。
            LocalDateTime arrivedAt = LocalDateTime.now();
            ReservationPo arrived = updateStatus(po, ReservationStatus.ARRIVED, po.getOrderId(), arrivedAt);
            // 到店是「客人已到」的事实登记，是开台与超时判定的依据，此前成功/失败都没有留痕。
            recordReservationAudit("reservation.arrival", "预约到店", arrived,
                    "{\"reservationNo\":" + jsonText(arrived.getReservationNo())
                            + ",\"beforeStatus\":\"CONFIRMED\",\"afterStatus\":" + jsonText(arrived.getStatus())
                            + ",\"arrivedAt\":" + jsonText(String.valueOf(arrived.getArrivedAt())) + "}");
            return arrived;
        } catch (RuntimeException failure) {
            recordFailure("reservation.arrival", id, failure);
            throw failure;
        }
    }

    /**
     * 标记「未到店」：到店前（PENDING/CONFIRMED）且已过预约开始时间 → NO_SHOW。
     *
     * <p>状态机里 NO_SHOW 此前**没有任何写入点**：客户放了鸽子，预约会永远停在 PENDING/CONFIRMED，
     * 后台「已预订」的包厢既不会被释放也不会提示，只能靠人工取消。本入口补上这个异常分支：
     * <ol>
     *   <li>非 PENDING/CONFIRMED → 409 {@code RESERVATION_STATUS_INVALID}（已到店/已开台不可标未到店）；</li>
     *   <li>已回填 order_id（已开台）→ 409 {@code RESERVATION_STATUS_INVALID}；</li>
     *   <li>预约开始时间还没到 → 409 {@code RESERVATION_NOT_STARTED}（没到点谈不上「未到店」）；</li>
     *   <li>预约不存在 → 404 {@code RESERVATION_NOT_FOUND}。</li>
     * </ol>
     *
     * <p>预约本身不占资源占用（见 {@link #assignRoom}），因此只改状态 + 留痕，不释放占用、不碰订单。
     */
    @Transactional
    public ReservationPo noShow(Long id) {
        try {
            ReservationPo po = require(id);
            String beforeStatus = po.getStatus();
            if (!ReservationStatus.PENDING.name().equals(beforeStatus)
                    && !ReservationStatus.CONFIRMED.name().equals(beforeStatus)) {
                throw new ApiException(409, "RESERVATION_STATUS_INVALID", noShowConflictMessage(po));
            }
            if (po.getOrderId() != null) {
                throw new ApiException(409, "RESERVATION_STATUS_INVALID", noShowConflictMessage(po));
            }
            if (po.getStartAt() != null && po.getStartAt().isAfter(LocalDateTime.now())) {
                throw new ApiException(409, "RESERVATION_NOT_STARTED",
                        "预约开始时间还没到（" + po.getStartAt() + "），不能标记未到店");
            }
            ReservationPo noShow = updateStatus(po, ReservationStatus.NO_SHOW, po.getOrderId());
            recordReservationAudit("reservation.no_show", "预约未到店", noShow,
                    "{\"reservationNo\":" + jsonText(noShow.getReservationNo())
                            + ",\"beforeStatus\":" + jsonText(beforeStatus)
                            + ",\"afterStatus\":" + jsonText(noShow.getStatus())
                            + ",\"startAt\":" + jsonText(String.valueOf(noShow.getStartAt())) + "}");
            return noShow;
        } catch (RuntimeException failure) {
            recordFailure("reservation.no_show", id, failure);
            throw failure;
        }
    }

    /** 不可标记未到店时的中文提示：已到店/已开台的要明确引导改走「取消订单」。 */
    private static String noShowConflictMessage(ReservationPo po) {
        if (po.getOrderId() != null || ReservationStatus.ARRIVED.name().equals(po.getStatus())
                || ReservationStatus.CONVERTED.name().equals(po.getStatus())) {
            return "该预约已到店或已开台，不能标记未到店";
        }
        if (ReservationStatus.NO_SHOW.name().equals(po.getStatus())) {
            return "该预约已标记未到店，请勿重复标记";
        }
        return "仅到店前（PENDING/CONFIRMED）预约可标记未到店，当前状态：" + po.getStatus();
    }

    /**
     * 到店分配具体包厢（规格 §3）：把预约落到该房型下的某个包厢，**状态不变**（锁房 ≠ 客人到店）。
     *
     * <p>校验顺序与错误码：
     * <ol>
     *   <li>预约不存在 → 404 {@code RESERVATION_NOT_FOUND}；</li>
     *   <li>已取消/未到店/已开台 → 409 {@code RESERVATION_STATUS_INVALID}；
     *       状态保持 PENDING/CONFIRMED 原值，到店事实由「到店登记」或「到店开台」写入
     *       {@code ord_reservation.arrived_at}（V25 迁移）；</li>
     *   <li>历史预约没有房型（只有 resource_id）→ 409 {@code RESERVATION_ROOM_TYPE_REQUIRED}，
     *       不给历史数据「改派包厢」的隐式能力（要改派请先到资源域绑定房型或走转台）；</li>
     *   <li>已分配同一包厢 → 幂等成功（不改库、不重复留痕）；</li>
     *   <li>已分配其它包厢且未显式 override → 409 {@code RESERVATION_ROOM_ASSIGNED}；</li>
     *   <li>包厢不存在 → 404 {@code RESOURCE_NOT_FOUND}；与预约房型不符 → 400 {@code ROOM_TYPE_MISMATCH}；
     *       与预约门店不符 → 400 {@code ROOM_STORE_MISMATCH}；</li>
     *   <li>包厢当前不可分配（使用中/清洁中）→ 409 {@code ROOM_UNAVAILABLE}；房态服务不可达 → 503
     *       {@code RESOURCE_STATE_UNAVAILABLE}（fail-closed，不降级放行）。</li>
     * </ol>
     *
     * @param override 已分配包厢时是否允许改派（true = 显式覆盖并留痕）
     */
    @Transactional
    public ReservationPo assignRoom(Long reservationId, Long resourceId, boolean override) {
        try {
            return doAssignRoom(reservationId, resourceId, override);
        } catch (RuntimeException failure) {
            // 分配包厢失败留痕（状态冲突/房型不符/包厢不可用/并发冲突）：/business/** 无 BFF 兜底。
            recordFailure("reservation.assign_room", reservationId, failure);
            throw failure;
        }
    }

    private ReservationPo doAssignRoom(Long reservationId, Long resourceId, boolean override) {
        ReservationPo po = require(reservationId);
        if (resourceId == null) {
            throw new ApiException(400, "RESOURCE_ID_REQUIRED", "缺少 resourceId");
        }
        if (!ReservationStatus.PENDING.name().equals(po.getStatus())
                && !ReservationStatus.CONFIRMED.name().equals(po.getStatus())
                && !ReservationStatus.ARRIVED.name().equals(po.getStatus())) {
            throw new ApiException(409, "RESERVATION_STATUS_INVALID", "仅未取消未开台的预约可分配包厢");
        }
        if (po.getRoomTypeId() == null) {
            throw new ApiException(409, "RESERVATION_ROOM_TYPE_REQUIRED",
                    "该预约未记录房型（历史预约），不能分配包厢，请改用开台或转台");
        }
        // 幂等：重复提交同一个包厢直接成功（后台按钮重试/网络重发不应报错，也不应重复留痕）。
        if (resourceId.equals(po.getResourceId())) {
            return po;
        }
        if (po.getResourceId() != null && !override) {
            throw new ApiException(409, "RESERVATION_ROOM_ASSIGNED",
                    "该预约已分配包厢，如需改派请显式确认覆盖");
        }
        if (resourceStateClient == null) {
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE", "房态服务不可用，无法校验包厢，请稍后重试");
        }

        Long previousResourceId = po.getResourceId();
        String previousResourceName = po.getResourceName();
        ResourceStateClient.RoomSnapshot room = resourceStateClient.requireRoom(resourceId);
        if (room.roomTypeId() == null || !room.roomTypeId().equals(po.getRoomTypeId())) {
            throw new ApiException(400, "ROOM_TYPE_MISMATCH", "所选包厢不属于该预约的房型，请重新选择");
        }
        if (room.storeId() != null && !room.storeId().equals(po.getStoreId())) {
            throw new ApiException(400, "ROOM_STORE_MISMATCH", "所选包厢不属于该预约的门店，请重新选择");
        }
        ResourceStateClient.RoomState state = resourceStateClient.requireState(resourceId);
        if (!state.available()) {
            throw new ApiException(409, "ROOM_UNAVAILABLE",
                    "包厢「" + (room.name() == null ? resourceId : room.name()) + "」"
                            + (state.reason() == null ? "当前不可分配" : state.reason() + "，暂不可分配"));
        }
        // 时段冲突（fail-closed）：房态只反映「此刻」的占用，而预约锁的是**时段**。
        // 预约本身不写资源占用（见类注释），所以「今晚 20:00 已被别的预约指定到同一包厢」在房态上
        // 仍是空闲；不查这一条，同一包厢同一个时段可以被两个预约同时占用。
        ReservationPo conflict = findResourceOverlap(po, resourceId);
        if (conflict != null) {
            throw new ApiException(409, "ROOM_RESERVED_OVERLAP",
                    "包厢「" + (room.name() == null ? resourceId : room.name()) + "」在 "
                            + formatWindow(po) + " 已被预约 " + conflict.getReservationNo()
                            + " 占用（" + formatWindow(conflict) + "），请改选其它包厢或时段");
        }

        ReservationPo updated = writeAssignment(po, resourceId, room.name());
        // 审计（含 before/after）：换包厢与首次分配都留痕，便于复盘「谁在什么时候把哪个预约指到了哪个包厢」。
        auditAssignRoom(updated, previousResourceId, previousResourceName, resourceId, room.name(), override);
        return updated;
    }

    /**
     * 该预约在指定包厢上的**时段冲突**预约（同门店 + 同包厢 + 时段重叠 + 仍占用时段的状态）。
     *
     * <p>重叠判定与超订软保护同一口径（左闭右开）：{@code other.startAt < this.endAt && other.endAt > this.startAt}，
     * 于是「上一场 22:00 结束、下一场 22:00 开始」不算冲突。
     *
     * <p>只把<b>仍然占用时段</b>的状态算作冲突：{@code PENDING / CONFIRMED / ARRIVED}。
     * {@code CANCELLED / NO_SHOW} 已释放时段；{@code CONVERTED}（已开台）的时段归属由**资源占用**
     * 承载（房态校验 + 开台占用同一包厢会 409），不再用预约行重复判一次。
     */
    private ReservationPo findResourceOverlap(ReservationPo po, Long resourceId) {
        if (resourceId == null || po.getStartAt() == null || po.getEndAt() == null) {
            return null;
        }
        List<ReservationPo> conflicts = reservationMapper.selectList(new LambdaQueryWrapper<ReservationPo>()
                .eq(ReservationPo::getStoreId, po.getStoreId())
                .eq(ReservationPo::getResourceId, resourceId)
                .ne(ReservationPo::getId, po.getId())
                .in(ReservationPo::getStatus, OCCUPYING_STATUSES)
                .lt(ReservationPo::getStartAt, po.getEndAt())
                .gt(ReservationPo::getEndAt, po.getStartAt())
                .orderByAsc(ReservationPo::getStartAt));
        return conflicts == null || conflicts.isEmpty() ? null : conflicts.get(0);
    }

    /** 预约时段仍被占用的状态（分配包厢的时段冲突判定用）。 */
    private static final List<String> OCCUPYING_STATUSES = List.of(
            ReservationStatus.PENDING.name(), ReservationStatus.CONFIRMED.name(),
            ReservationStatus.ARRIVED.name());

    /** 预约时段的展示文案（门店本地墙上时间，如 {@code 09-19 20:00–23:00}）。 */
    private static String formatWindow(ReservationPo po) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        LocalDateTime start = po.getStartAt();
        LocalDateTime end = po.getEndAt();
        if (start == null || end == null) {
            return "所选时段";
        }
        return start.format(formatter) + "–" + end.format(formatter);
    }

    /**
     * 「到店分配包厢」的候选包厢列表（后台弹窗的唯一数据源）。
     *
     * <p>为什么由服务端算，而不是前端把资源列表拼起来：候选是否可分配取决于三件事，
     * 其中两件前端拿不全（房态运行态、本时段的预约冲突），任何一处读不到都会退化成
     * 「把不可分配的包厢列成可选」，运营选中后只能被后端 409 拒绝。
     * 因此这里一次算完，并把<b>不可分配的原因</b>一并回给界面：
     * <ol>
     *   <li>包厢状态与运行态（启用中、非清洁中、无使用中/已预订占用）—— 资源域；</li>
     *   <li>该包厢在<b>本预约时段</b>是否已被其它未取消预约占用 —— 预约表；</li>
     *   <li>是否就是当前已分配的包厢（仍可重复选择，幂等）。</li>
     * </ol>
     * 房态读不到时失败关闭（{@code RESOURCE_STATE_UNAVAILABLE}），不返回空列表。
     */
    public List<AssignableRoom> assignableRooms(Long reservationId) {
        ReservationPo po = require(reservationId);
        if (!ReservationStatus.PENDING.name().equals(po.getStatus())
                && !ReservationStatus.CONFIRMED.name().equals(po.getStatus())
                && !ReservationStatus.ARRIVED.name().equals(po.getStatus())) {
            throw new ApiException(409, "RESERVATION_STATUS_INVALID", "仅未取消未开台的预约可分配包厢");
        }
        if (po.getRoomTypeId() == null) {
            throw new ApiException(409, "RESERVATION_ROOM_TYPE_REQUIRED",
                    "该预约未记录房型（历史预约），不能分配包厢，请改用开台或转台");
        }
        List<ResourceStateClient.RoomStateView> rooms =
                resourceStateClient.roomStates(RoomTypeCatalogClient.ROOM_RESOURCE_TYPE, po.getStoreId());
        Map<Long, ReservationPo> conflicts = overlappingByResource(po);
        List<AssignableRoom> result = new ArrayList<>();
        for (ResourceStateClient.RoomStateView room : rooms) {
            if (room.id() == null || !po.getRoomTypeId().equals(room.roomTypeId())) {
                continue;
            }
            ReservationPo conflict = conflicts.get(room.id());
            boolean currentAssignment = room.id().equals(po.getResourceId());
            String reason = null;
            if (!room.available()) {
                reason = room.unavailableReason() == null || room.unavailableReason().isBlank()
                        ? "当前不可分配（" + (room.state() == null ? "占用中" : room.state()) + "）"
                        : room.unavailableReason();
            } else if (conflict != null && !currentAssignment) {
                reason = "本时段已被预约 " + conflict.getReservationNo() + "（" + formatWindow(conflict) + "）占用";
            }
            boolean assignable = reason == null;
            result.add(new AssignableRoom(room.id(), room.name(), room.resourceCode(), room.roomTypeId(),
                    room.roomTypeName(), assignable, reason, currentAssignment,
                    conflict == null ? null : conflict.getReservationNo(),
                    conflict == null ? null : formatWindow(conflict), room.state()));
        }
        return result;
    }

    /** 本预约时段内、按包厢聚合的冲突预约（一个包厢最多取最早的一条用于提示）。 */
    private Map<Long, ReservationPo> overlappingByResource(ReservationPo po) {
        Map<Long, ReservationPo> result = new LinkedHashMap<>();
        if (po.getStartAt() == null || po.getEndAt() == null) {
            return result;
        }
        List<ReservationPo> conflicts = reservationMapper.selectList(new LambdaQueryWrapper<ReservationPo>()
                .eq(ReservationPo::getStoreId, po.getStoreId())
                .isNotNull(ReservationPo::getResourceId)
                .ne(ReservationPo::getId, po.getId())
                .in(ReservationPo::getStatus, OCCUPYING_STATUSES)
                .lt(ReservationPo::getStartAt, po.getEndAt())
                .gt(ReservationPo::getEndAt, po.getStartAt())
                .orderByAsc(ReservationPo::getStartAt));
        if (conflicts != null) {
            for (ReservationPo conflict : conflicts) {
                result.putIfAbsent(conflict.getResourceId(), conflict);
            }
        }
        return result;
    }

    /**
     * 分配包厢候选（服务端算好的可分配性与原因）。
     *
     * @param resourceId        包厢资源ID
     * @param assignable        是否可分配（false 时 reason 必填）
     * @param reason            不可分配原因（房态原因或「本时段已被预约 X 占用」）
     * @param currentAssignment 是否就是当前已分配的包厢（允许重复选择，幂等）
     * @param conflictReservationNo 时段冲突的预约号（无冲突为 null）
     * @param conflictWindow    时段冲突的时段文案（无冲突为 null）
     * @param roomState         资源域运行态（IDLE/CLEANING/OCCUPIED/MAINTENANCE）
     */
    public record AssignableRoom(Long resourceId, String name, String resourceCode, Long roomTypeId,
                                 String roomTypeName, boolean assignable, String reason, boolean currentAssignment,
                                 String conflictReservationNo, String conflictWindow, String roomState) {
    }

    /**
     * 到店预约开台（商户手动开台，KTV_BUSINESS_01 §1.1「到店/快速开台」）：
     * ARRIVED（已登记到店）或 CONFIRMED（客人已到、门店直接开台，**隐含到店**）→
     * 生成统一订单 + KTV 包厢会话并开台计时（订单 DRAFT→SERVING）→ 回填预约 order_id 并置 CONVERTED。
     *
     * <p>幂等：已回填 order_id 且订单存在时返回已有订单，不重复开台（幂等判断在状态校验**之前**，
     * 否则已开台（CONVERTED）的重复提交会撞状态校验返回 409 而不是既有订单）。
     *
     * <p>开台前必须有具体包厢：新预约由「到店分配包厢」写入；历史预约的 resource_id 仍是下单时选定的包厢，
     * 因此旧数据无需回填也能开台。
     */
    @Transactional
    public OrderPo openTable(Long reservationId, Integer freeWaitMinutes) {
        try {
            return doOpenTable(reservationId, freeWaitMinutes);
        } catch (RuntimeException failure) {
            // 预约开台失败留痕（状态/未分配包厢/占用失败/落库失败）：开台即开始计费，失败必须可回溯。
            recordFailure("reservation.open_table", reservationId, failure);
            throw failure;
        }
    }

    private OrderPo doOpenTable(Long reservationId, Integer freeWaitMinutes) {
        ReservationPo r = require(reservationId);

        // 幂等：已开台（order_id 已回填且订单存在）则直接返回已有订单，附上会话 id。
        // 必须放在状态校验之前：开台成功后预约已是 CONVERTED，重复提交应返回既有订单而不是 409。
        if (r.getOrderId() != null) {
            OrderPo existing = orderMapper.selectById(r.getOrderId());
            if (existing != null) {
                existing.setSessionId(ktvSessionService.findByOrderId(existing.getId()).getId());
                return existing;
            }
        }

        // CONFIRMED 开台 = 「客人到了，直接开台」：到店事实在此一并登记（arrived_at），
        // 不再强制运营先点一次「客户到店」；PENDING 仍需先确认，避免未确认的预约直接进计费。
        boolean arrivalImplied = ReservationStatus.CONFIRMED.name().equals(r.getStatus());
        if (!arrivalImplied && !ReservationStatus.ARRIVED.name().equals(r.getStatus())) {
            throw new BusinessException("RESERVATION_STATUS_INVALID",
                    "仅已确认（CONFIRMED）或已到店（ARRIVED）预约可开台，当前状态：" + r.getStatus());
        }
        if (r.getResourceId() == null) {
            throw new ApiException(409, "RESERVATION_ROOM_NOT_ASSIGNED",
                    "预约尚未分配具体包厢，请先分配包厢后再开台");
        }

        // 组织上下文：优先租户上下文，缺省回退门店 id（单组织租户下门店归属唯一组织）。
        TenantContext ctx = TenantContextHolder.get();
        Long organizationId = ctx == null ? null : ctx.organizationId();
        if (organizationId == null) {
            organizationId = r.getStoreId();
        }

        LocalDateTime now = LocalDateTime.now();
        OrderPo order = new OrderPo();
        order.setTenantId(r.getTenantId());
        order.setOrganizationId(organizationId);
        order.setStoreId(r.getStoreId());
        order.setOrderNo(dailySerialNumberGenerator.next(DailySerialNumberGenerator.DocType.ORDER,
                r.getTenantId()));
        order.setBusinessType(r.getBusinessType());
        order.setCustomerId(r.getCustomerId());
        order.setStatus("DRAFT");
        // 币种取当前请求租户币种（docs/standards/16_CURRENCY_CONVENTIONS.md §2/§3.3），
        // 不再硬编码 CNY：缺 claim 时 CurrencyResolver 回退 USD，绝不因缺 claim 失败。
        order.setCurrencyCode(CurrencyResolver.currentCode());
        order.setSubtotalAmount(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotalAmount(BigDecimal.ZERO);
        order.setPaidAmount(BigDecimal.ZERO);
        order.setRefundableAmount(BigDecimal.ZERO);
        order.setVersion(0);
        order.setCreatedBy(operatorId());
        order.setCreatedAt(now);
        order.setUpdatedBy(operatorId());
        order.setUpdatedAt(now);
        orderMapper.insert(order);

        // 创建包厢会话并开台计时：RESERVED → OPEN（订单 DRAFT → SERVING，开始计费）。
        // 预约人数随开台写入会话（party_size），订单列表与房态看板据此展示「人数」。
        KtvSessionPo session = ktvSessionService.create(r.getTenantId(), order.getId(), r.getResourceId());
        ktvSessionService.open(session.getId(), freeWaitMinutes, r.getPartySize());

        // 回填预约 order_id 并完成生命周期转换，避免 ARRIVED 记录长期悬挂。
        // CONFIRMED 直接开台时一并登记到店时间（arrivalImplied）：到店事实不能只靠状态推断。
        updateStatus(r, ReservationStatus.CONVERTED, order.getId(), arrivalImplied ? now : null);

        // 预约开台此前没有任何留痕：它同时产生订单与计费会话，是最需要可回溯的写操作。
        // 幂等键按预约 ID 稳定，重复提交（已回填 order_id）在上面的分支直接返回，不会重复留痕。
        if (auditClient != null) {
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(r.getTenantId())
                    .storeId(r.getStoreId())
                    .operatorId(operatorId())
                    .action("reservation.open_table")
                    .actionLabel("预约开台")
                    .resourceType("reservation")
                    .resourceId(String.valueOf(r.getId()))
                    .resourceName(r.getReservationNo())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .idempotencyKey("reservation-open-table:" + r.getId())
                    .detailJson("{\"reservationNo\":" + jsonText(r.getReservationNo())
                            + ",\"orderId\":" + order.getId()
                            + ",\"resourceId\":" + r.getResourceId()
                            + ",\"status\":" + jsonText(r.getStatus())
                            + ",\"arrivalImplied\":" + arrivalImplied + "}")
                    .build());
        }

        // 重新读取订单：开台后订单状态已由 DRAFT 流转为 SERVING，返回真实状态。
        order = orderMapper.selectById(order.getId());
        order.setSessionId(session.getId());
        return order;
    }

    /**
     * 取消预约：到店前（PENDING/CONFIRMED）→ CANCELLED，运营代客取消**必须填写原因**。
     *
     * <p>校验顺序与错误码：
     * <ol>
     *   <li>原因为空/空白 → 400 {@code CANCEL_REASON_REQUIRED}（「取消预约必须填写原因」）；
     *       超过 {@value CancelReasons#MAX_LENGTH} 字符 → 400 {@code CANCEL_REASON_TOO_LONG}；</li>
     *   <li>预约不存在 → 404 {@code RESERVATION_NOT_FOUND}；</li>
     *   <li>已到店（ARRIVED）/已开台（order_id 非空）/已取消（CANCELLED）/已转化（CONVERTED）
     *       → 409 {@code RESERVATION_STATUS_INVALID}：到店后包厢与订单已经产生，取消要走「取消订单」，
     *       否则会留下「预约取消了、订单还在计时」的悬挂。</li>
     * </ol>
     *
     * <p>原因本身没有落库列（ord_reservation 无 reason 列），因此**审计 {@code reservation.cancel}
     * 是原因的唯一留存处**：detailJson 带 beforeStatus/afterStatus/reason/reservationNo/roomTypeId。
     */
    @Transactional
    public ReservationPo cancel(Long id, String reason) {
        // 原因校验（无状态前置）留在 try 之外：参数非法不产生业务副作用，不为它留失败痕迹。
        String normalizedReason = CancelReasons.require(reason, "取消预约必须填写原因");
        try {
            ReservationPo po = require(id);
            String beforeStatus = po.getStatus();
            if (!ReservationStatus.PENDING.name().equals(beforeStatus)
                    && !ReservationStatus.CONFIRMED.name().equals(beforeStatus)) {
                throw new ApiException(409, "RESERVATION_STATUS_INVALID", cancelConflictMessage(po));
            }
            ReservationPo cancelled = updateStatus(po, ReservationStatus.CANCELLED, po.getOrderId());
            auditCancel(cancelled, beforeStatus, normalizedReason);
            return cancelled;
        } catch (RuntimeException failure) {
            // 取消失败留痕（预约不存在/状态已到店或已开台/并发冲突）：与成功路径同码 order=reservation.cancel。
            recordFailure("reservation.cancel", id, failure);
            throw failure;
        }
    }

    /**
     * 预约写操作失败留痕：动作码与成功路径同码，{@code result=FAILURE} + 稳定 errorCode
     * （{@link ApiException}/{@link BusinessException} 的业务码优先，退化到异常类名并按列宽截断）。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（同一动作重复失败必须各自留痕，也不覆盖成功路径的稳定键）；
     * detail 只放预约标识，**不含**联系人/手机号（预约 contact 字段绝不进审计详情）。
     */
    private void recordFailure(String action, Long reservationId, RuntimeException failure) {
        if (auditClient == null) {
            return;
        }
        TenantContext ctx = TenantContextHolder.get();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(ctx == null ? null : ctx.tenantId())
                .storeId(ctx == null ? null : ctx.storeId())
                .operatorId(operatorId())
                .action(action)
                .resourceType("reservation")
                .resourceId(reservationId == null ? null : String.valueOf(reservationId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"reservationId\":" + reservationId + "}")
                .build());
    }

    /** 预约成功留痕（欠补齐动作：确认/到店）：与失败同码，detail 只放预约号与前后状态。 */
    private void recordReservationAudit(String action, String actionLabel, ReservationPo po, String detailJson) {
        if (auditClient == null) {
            return;
        }
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(po.getTenantId())
                .storeId(po.getStoreId())
                .operatorId(operatorId())
                .action(action)
                .actionLabel(actionLabel)
                .resourceType("reservation")
                .resourceId(String.valueOf(po.getId()))
                .resourceName(po.getReservationNo())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson(detailJson)
                .build());
    }

    /** 不可取消时的中文提示：到店/已开台的要明确引导改走「取消订单」，避免运营在后台反复试。 */
    private static String cancelConflictMessage(ReservationPo po) {
        if (ReservationStatus.CANCELLED.name().equals(po.getStatus())) {
            return "该预约已取消，请勿重复取消";
        }
        if (po.getOrderId() != null || ReservationStatus.ARRIVED.name().equals(po.getStatus())
                || ReservationStatus.CONVERTED.name().equals(po.getStatus())) {
            return "该预约已到店或已开台，不能取消预约，请改走「取消订单」取消对应订单";
        }
        return "仅到店前（PENDING/CONFIRMED）预约可取消，当前状态：" + po.getStatus();
    }

    /** 取消预约审计：动作码 {@code reservation.cancel}，detail 带前后状态与原因（历史行 roomTypeId 可为 null）。 */
    private void auditCancel(ReservationPo po, String beforeStatus, String reason) {
        if (auditClient == null) {
            return;
        }
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(po.getTenantId())
                .storeId(po.getStoreId())
                .operatorId(operatorId())
                .action("reservation.cancel")
                .actionLabel("预约取消")
                .resourceType("reservation")
                .resourceId(String.valueOf(po.getId()))
                .resourceName(po.getReservationNo())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey("reservation-cancel:" + po.getId())
                .detailJson(cancelDetail(po, beforeStatus, reason))
                .build());
    }

    private static String cancelDetail(ReservationPo po, String beforeStatus, String reason) {
        return "{\"reservationNo\":" + jsonText(po.getReservationNo())
                + ",\"roomTypeId\":" + po.getRoomTypeId()
                + ",\"beforeStatus\":" + jsonText(beforeStatus)
                + ",\"afterStatus\":" + jsonText(po.getStatus())
                + ",\"reason\":" + jsonText(reason) + "}";
    }

    // ------------------------------------------------------------------ 房型校验 / 超订软保护

    /**
     * 房型必须存在、启用、属于该门店（资源域按 storeId 过滤，跨门店房型读不到即视为无效）。
     * 资源域不可达时 {@link RoomTypeCatalogClient} 抛 {@code RESOURCE_STATE_UNAVAILABLE}（fail-closed）。
     */
    private RoomTypeView requireBookableRoomType(Long storeId, Long roomTypeId) {
        if (roomTypeCatalogClient == null) {
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE", "房型服务不可用，暂时无法创建预约，请稍后重试");
        }
        RoomTypeView roomType = roomTypeCatalogClient.roomType(storeId, roomTypeId)
                .orElseThrow(() -> new ApiException(400, "ROOM_TYPE_INVALID", "房型不存在或不属于当前门店，请重新选择"));
        if (!roomType.enabled()) {
            throw new ApiException(400, "ROOM_TYPE_DISABLED",
                    "房型「" + (roomType.name() == null ? roomTypeId : roomType.name()) + "」已停用，暂不可预约");
        }
        return roomType;
    }

    /**
     * 营业时间校验（**所有预约入口统一走这一条规则**：C 端下单、B 端/后台代客预约、BFF 代理都一样）。
     *
     * <p>规则：预约**到店时间**（{@code startAt} 的门店本地时刻）必须落在营业时段
     * {@code [open, close)} 内；营业时间跨自然日（默认 18:00–05:00）是常态，因此凌晨的到店时间
     * 属于**前一个营业日**的时段，不是「越界」。
     *
     * <p>失败语义：租户服务读不到营业时间时**跳过校验并记 WARN**（放行），不用缺省窗口硬拦
     * —— 各门店营业时间可能不同，误拦的代价（客人下不了单）远大于「本次未校验到」。
     * 预约结束时间不校验：KTV 允许最后一场跨过打烊时间（营业时间约束的是**到店**时点）。
     */
    private void requireWithinBusinessHours(CreateReservationCommand cmd) {
        LocalDateTime startAt = toBusinessLocal(cmd.startAt());
        if (startAt == null) {
            return;
        }
        TenantBusinessHoursClient.BusinessHours hours = businessHoursClient.resolve(cmd.storeId()).orElse(null);
        if (hours == null) {
            log.warn("营业时间不可用，本次跳过营业时间校验: storeId={}, startAt={}", cmd.storeId(), startAt);
            return;
        }
        if (!hours.contains(startAt.toLocalTime())) {
            throw new ApiException(422, "RESERVATION_OUT_OF_BUSINESS_HOURS",
                    "预约到店时间 " + startAt.toLocalTime().format(BUSINESS_TIME_FORMAT)
                            + " 不在营业时间内（" + hours.displayText() + "），请改选营业时段内的到店时间");
        }
    }

    /** 营业时间文案用的时刻格式（HH:mm）。 */
    private static final DateTimeFormatter BUSINESS_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 生效营业时间（供客户端约束「到店时间」选择器：C 端下单页、后台预约页）。
     * 与创建校验**同源**（同一个租户域接口），确保「界面能选」与「服务端放行」永远一致。
     */
    public TenantBusinessHoursClient.BusinessHours businessHours(Long storeId) {
        return businessHoursClient.resolve(storeId).orElseGet(TenantBusinessHoursClient::defaultHours);
    }

    /** 该房型必须至少有 1 个启用包厢，否则「预约得出去、到店分不出房」。 */
    private long requireEnabledRooms(Long storeId, RoomTypeView roomType) {
        long enabledRooms = roomTypeCatalogClient.enabledRoomCount(storeId, roomType.id());
        if (enabledRooms <= 0) {
            throw new ApiException(409, "ROOM_TYPE_NO_ROOM_AVAILABLE",
                    "房型「" + (roomType.name() == null ? roomType.id() : roomType.name())
                            + "」当前没有可预约的包厢，请改选其它房型或时段");
        }
        return enabledRooms;
    }

    /**
     * 仍然占用「预约名额」的状态：待确认 / 已确认 / 已到店（未开台）。
     *
     * <p>**只有这些算名额**。已取消（CANCELLED）、未到店（NO_SHOW）自然不算；
     * 尤其**已开台（CONVERTED）不能算**：预约一旦兑现成会话，包厢的真实占用已经由资源域的
     * {@code res_occupation} / 会话状态硬校验，再把它算进预约数就是**同一间房算两次**——
     * 客人开台后（甚至早早结束、包厢已经空闲）这个时段仍会一直显示「已满」，
     * 让 C 端今天根本约不出小包（真实缺陷：DEBUG 现场 3 间小包被 3 条 CONVERTED 预约锁死）。
     */
    private static final List<String> SLOT_HOLDING_STATUSES = List.of(
            ReservationStatus.PENDING.name(),
            ReservationStatus.CONFIRMED.name(),
            ReservationStatus.ARRIVED.name());

    /**
     * 超订软保护（规格 §3）：同门店 + 同房型 + 时段重叠（左闭右开）的**仍占名额**预约数
     * ≥ 该房型启用包厢数 → 409 {@code ROOM_TYPE_FULL}；统计口径见 {@link #SLOT_HOLDING_STATUSES}。
     *
     * <p>这是软保护而非锁房：预约阶段不占用具体包厢，实际可用性以到店分配为准
     * （分配时用 {@link ResourceStateClient} 的占用门禁做硬校验）。
     * 重叠判定按左闭右开：{@code existing.startAt < newEndAt && existing.endAt > newStartAt}，
     * 于是「上一场 22:00 结束、下一场 22:00 开始」不算重叠。
     */
    private void requireRoomTypeNotOverbooked(Long tenantId, CreateReservationCommand cmd, long enabledRooms) {
        LocalDateTime startAt = toBusinessLocal(cmd.startAt());
        LocalDateTime endAt = toBusinessLocal(cmd.endAt());
        Long booked = reservationMapper.selectCount(new LambdaQueryWrapper<ReservationPo>()
                .eq(ReservationPo::getTenantId, tenantId)
                .eq(ReservationPo::getStoreId, cmd.storeId())
                .eq(ReservationPo::getRoomTypeId, cmd.roomTypeId())
                .in(ReservationPo::getStatus, SLOT_HOLDING_STATUSES)
                .lt(ReservationPo::getStartAt, endAt)
                .gt(ReservationPo::getEndAt, startAt));
        long overlapping = booked == null ? 0L : booked;
        if (overlapping >= enabledRooms) {
            throw new ApiException(409, "ROOM_TYPE_FULL",
                    "该房型在所选时段已约满（" + enabledRooms + " 间），请改选其它房型或时段");
        }
    }

    // ------------------------------------------------------------------ helpers

    private ReservationPo require(Long id) {
        ReservationPo po = reservationMapper.selectById(id);
        if (po == null) {
            throw new BusinessException("RESERVATION_NOT_FOUND", "预约不存在");
        }
        return po;
    }

    private void requireStatus(ReservationPo po, ReservationStatus expected, String action) {
        if (!expected.name().equals(po.getStatus())) {
            throw new BusinessException("RESERVATION_STATUS_INVALID",
                    "仅 " + expected.name() + " 预约可" + action);
        }
    }

    private void assertVersion(ReservationPo po, Integer expectedVersion) {
        int current = po.getVersion() == null ? 0 : po.getVersion();
        if (expectedVersion != null && !Integer.valueOf(current).equals(expectedVersion)) {
            throw new BusinessException("RESERVATION_VERSION_CONFLICT", "预约版本冲突，请重试");
        }
    }

    /** 乐观锁状态流转：WHERE version = 旧值，version + 1；更新 0 行视为并发冲突。 */
    private ReservationPo updateStatus(ReservationPo po, ReservationStatus target, Long orderId) {
        return updateStatus(po, target, orderId, null);
    }

    /**
     * 乐观锁状态流转（带可选到店时间）：{@code arrivedAt} 非空时一并写 {@code arrived_at}，
     * 为空则保持原值（不覆盖此前登记的到店时间）。
     */
    private ReservationPo updateStatus(ReservationPo po, ReservationStatus target, Long orderId,
                                       LocalDateTime arrivedAt) {
        int oldVersion = po.getVersion() == null ? 0 : po.getVersion();
        LocalDateTime now = LocalDateTime.now();

        LambdaUpdateWrapper<ReservationPo> uw = new LambdaUpdateWrapper<ReservationPo>()
                .eq(ReservationPo::getId, po.getId())
                .eq(ReservationPo::getVersion, oldVersion)
                .set(ReservationPo::getStatus, target.name())
                .set(ReservationPo::getVersion, oldVersion + 1)
                .set(ReservationPo::getUpdatedBy, operatorId())
                .set(ReservationPo::getUpdatedAt, now);
        if (orderId != null) {
            uw.set(ReservationPo::getOrderId, orderId);
        }
        if (arrivedAt != null) {
            uw.set(ReservationPo::getArrivedAt, arrivedAt);
        }

        int updated = reservationMapper.update(null, uw);
        if (updated == 0) {
            throw new BusinessException("RESERVATION_VERSION_CONFLICT", "预约已被并发修改，请重试");
        }

        po.setStatus(target.name());
        po.setVersion(oldVersion + 1);
        po.setUpdatedBy(operatorId());
        po.setUpdatedAt(now);
        if (orderId != null) {
            po.setOrderId(orderId);
        }
        if (arrivedAt != null) {
            po.setArrivedAt(arrivedAt);
        }
        return po;
    }

    /**
     * 分配包厢落库：**只写 resource_id，状态保持不变**（PENDING 仍是待确认、CONFIRMED 仍是已预订）。
     *
     * <p>「预约锁房」与「客人到店」是两件事：运营经常在客人到店前就把包厢提前指定好（备房、排班），
     * 此前这里同时把状态置 ARRIVED，导致后台看板对未到店的预约显示「客户已到店」并直接给出「到店开台」，
     * 把「已预订」的包厢误算成「客人已到店」。做到店登记只能走
     * {@link #arrival}（CONFIRMED → ARRIVED）或 {@link #openTable}（到店并开台，隐含到店）。
     */
    private ReservationPo writeAssignment(ReservationPo po, Long resourceId, String resourceName) {
        int oldVersion = po.getVersion() == null ? 0 : po.getVersion();
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<ReservationPo> uw = new LambdaUpdateWrapper<ReservationPo>()
                .eq(ReservationPo::getId, po.getId())
                .eq(ReservationPo::getVersion, oldVersion)
                .set(ReservationPo::getResourceId, resourceId)
                .set(ReservationPo::getVersion, oldVersion + 1)
                .set(ReservationPo::getUpdatedBy, operatorId())
                .set(ReservationPo::getUpdatedAt, now);
        int updated = reservationMapper.update(null, uw);
        if (updated == 0) {
            throw new BusinessException("RESERVATION_VERSION_CONFLICT", "预约已被并发修改，请重试");
        }
        po.setResourceId(resourceId);
        po.setResourceName(resourceName);
        po.setVersion(oldVersion + 1);
        po.setUpdatedBy(operatorId());
        po.setUpdatedAt(now);
        return po;
    }

    /** 分配包厢审计：动作码 {@code reservation.assign_room}，detail 带 before/after（含换包厢）。 */
    private void auditAssignRoom(ReservationPo po, Long beforeResourceId, String beforeResourceName,
                                 Long afterResourceId, String afterResourceName, boolean override) {
        if (auditClient == null) {
            return;
        }
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(po.getTenantId())
                .storeId(po.getStoreId())
                .operatorId(operatorId())
                .action("reservation.assign_room")
                .actionLabel("预约分配包厢")
                .resourceType("reservation")
                .resourceId(String.valueOf(po.getId()))
                .resourceName(po.getReservationNo())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey("reservation-assign-room:" + po.getId() + ":" + afterResourceId)
                .detailJson(assignRoomDetail(po, beforeResourceId, beforeResourceName, afterResourceId,
                        afterResourceName, override))
                .build());
    }

    private static String assignRoomDetail(ReservationPo po, Long beforeResourceId, String beforeResourceName,
                                           Long afterResourceId, String afterResourceName, boolean override) {
        return "{\"reservationNo\":" + jsonText(po.getReservationNo())
                + ",\"roomTypeId\":" + po.getRoomTypeId()
                + ",\"status\":" + jsonText(po.getStatus())
                + ",\"override\":" + override
                + ",\"before\":{\"resourceId\":" + beforeResourceId
                + ",\"resourceName\":" + jsonText(beforeResourceName) + "}"
                + ",\"after\":{\"resourceId\":" + afterResourceId
                + ",\"resourceName\":" + jsonText(afterResourceName) + "}}";
    }

    /** 最小 JSON 字符串转义（避免为一次审计上报引入额外的序列化依赖）。 */
    private static String jsonText(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private ReservationPo findByIdempotencyKey(Long tenantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return reservationMapper.selectOne(new LambdaQueryWrapper<ReservationPo>()
                .eq(ReservationPo::getTenantId, tenantId)
                .eq(ReservationPo::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
    }

    private void validateCreate(CreateReservationCommand cmd) {
        if (cmd == null || cmd.businessType() == null || cmd.businessType().isBlank()) {
            throw new ApiException(400, "BUSINESS_TYPE_REQUIRED", "缺少 businessType");
        }
        if (cmd.storeId() == null) {
            throw new ApiException(400, "STORE_ID_REQUIRED", "缺少 storeId");
        }
        if (cmd.roomTypeId() == null) {
            throw new ApiException(400, "ROOM_TYPE_REQUIRED",
                    "缺少 roomTypeId：预约按房型（包厢类型）创建，具体包厢到店后由门店分配");
        }
        if (cmd.startAt() == null || cmd.endAt() == null) {
            throw new ApiException(400, "RESERVATION_TIME_REQUIRED", "缺少预约时间");
        }
        if (!cmd.endAt().isAfter(cmd.startAt())) {
            throw new ApiException(400, "RESERVATION_TIME_INVALID", "endAt 必须晚于 startAt");
        }
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new BusinessException("SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
    }

    private Long operatorId() {
        TenantContext ctx = TenantContextHolder.get();
        return ctx == null ? 0L : ctx.accountId();
    }

    /**
     * 读路径回填房型名/编码与包厢名（列表/详情展示「包厢类型」与已分配包厢）。
     *
     * <p>每个门店只查一次房型字典与包厢列表（按 storeId 分组去重，避免逐行 N+1 调资源域）；
     * 资源域不可达时**只丢展示字段**（字段留 null，前端按「未知房型/未分配」展示），
     * 不因为展示数据拿不到就推翻预约列表本身。
     *
     * <p>历史预约（无 room_type_id、只有 resource_id）同样按 resource_id 回填包厢名，
     * 于是旧数据在列表里仍显示当初选定的包厢。
     */
    private List<ReservationPo> enrichDisplayFields(List<ReservationPo> rows) {
        if (rows == null || rows.isEmpty() || roomTypeCatalogClient == null || resourceStateClient == null) {
            return rows;
        }
        Map<Long, Map<Long, RoomTypeView>> roomTypesByStore = new HashMap<>();
        Map<Long, Map<Long, RoomView>> roomsByStore = new HashMap<>();
        for (ReservationPo po : rows) {
            if (po.getStoreId() == null) {
                continue;
            }
            Long storeId = po.getStoreId();
            if (po.getRoomTypeId() != null) {
                RoomTypeView roomType = roomTypesByStore
                        .computeIfAbsent(storeId, this::roomTypesOfStore)
                        .get(po.getRoomTypeId());
                if (roomType != null) {
                    po.setRoomTypeCode(roomType.code());
                    po.setRoomTypeName(roomType.name());
                }
            }
            if (po.getResourceId() != null) {
                RoomView room = roomsByStore
                        .computeIfAbsent(storeId, this::roomsOfStore)
                        .get(po.getResourceId());
                if (room != null) {
                    po.setResourceName(room.name());
                }
            }
        }
        return rows;
    }

    /** 门店房型字典（读路径降级：读不到返回空映射，不影响预约列表本身）。 */
    private Map<Long, RoomTypeView> roomTypesOfStore(Long storeId) {
        try {
            return roomTypeCatalogClient.roomTypes(storeId).stream()
                    .filter(view -> view.id() != null)
                    .collect(Collectors.toMap(RoomTypeView::id, Function.identity(), (first, second) -> first));
        } catch (RuntimeException e) {
            log.warn("Room types unavailable for display, degrade to null fields: storeId={}", storeId, e);
            return Map.of();
        }
    }

    /** 门店包厢列表（读路径降级：读不到返回空映射，历史预约仍可展示其它字段）。 */
    private Map<Long, RoomView> roomsOfStore(Long storeId) {
        try {
            return roomTypeCatalogClient.rooms(storeId).stream()
                    .filter(view -> view.id() != null)
                    .collect(Collectors.toMap(RoomView::id, Function.identity(), (first, second) -> first));
        } catch (RuntimeException e) {
            log.warn("Rooms unavailable for display, degrade to null fields: storeId={}", storeId, e);
            return Map.of();
        }
    }

    /**
     * 统一转为北京时间（UTC+8）的本地墙上时间落库；任何带时区的入参都归一化到 +08:00。
     * 返回值不含偏移信息，读路径直接按门店本地时间展示（与 ord_reservation 列注释一致）。
     */
    private LocalDateTime toBusinessLocal(OffsetDateTime odt) {
        return odt == null ? null : odt.withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 创建预约命令（controller 层组装后传入）。
     *
     * <p>只有房型，没有具体包厢：{@code resourceId} 不再由调用方提供
     * （controller 层对传入 resourceId 的请求直接 400 {@code RESOURCE_ID_NOT_ALLOWED}），
     * 到店后由「分配包厢」写入。
     *
     * @param roomTypeId 预约房型（res_room_type.id，必填）
     * @param startAt 预约开始（带偏移）；落库前归一到门店营业本地时间 +08:00
     * @param endAt   预约结束（带偏移），必须晚于 startAt
     */
    public record CreateReservationCommand(
            String businessType,
            Long customerId,
            Long storeId,
            Long roomTypeId,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            Integer partySize,
            String contact) {
    }
}
