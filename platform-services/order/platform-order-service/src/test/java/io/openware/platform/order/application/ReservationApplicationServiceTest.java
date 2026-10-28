package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.currency.CurrencyContextHolder;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.order.application.dto.OrderView;
import io.openware.platform.order.api.dto.ReservationView;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.domain.inventory.repository.StoreScopeRepository;
import io.openware.platform.order.domain.reservation.model.Reservation;
import io.openware.platform.order.domain.reservation.model.ReservationStatus;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository.AuditSnapshot;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository.Persisted;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository.WriteAudit;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.client.RoomTypeCatalogClient;
import io.openware.platform.order.infra.client.RoomTypeCatalogClient.RoomTypeView;
import io.openware.platform.order.infra.client.RoomTypeCatalogClient.RoomView;
import io.openware.platform.order.infra.client.TenantBusinessHoursClient;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.RefundStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 预约改为「预约房型」后的核心规则（docs/renovation/KTV_RESERVATION_ROOM_TYPE.md §3/§5）：
 * 创建只认房型、房型校验与超订软保护、到店分配包厢（含幂等/覆盖/审计 before-after/fail-closed）、
 * 开台前置包厢、旧数据（只有 resource_id）兼容。
 *
 * <p><b>持久化替身换成领域端口</b>：测试现在装配 mock 的 {@link ReservationRepository}，
 * 断言的是「端口被怎么调用」与「聚合/视图的业务事实」，而不是 {@code LambdaUpdateWrapper} 的 SQL 形状。
 * 两类断言因此搬家：
 * <ul>
 *   <li>「状态集合只含 PENDING/CONFIRMED/ARRIVED」由端口方法名表达（{@code countSlotHolding}），
 *       具体 SQL 的状态集合钉在 {@code ReservationRepositoryAdapterTest}；</li>
 *   <li>「分配包厢只写 resource_id、不写 status」同样由 {@code updateAssignmentWithVersion} 的列契约表达，
 *       SET 子句的列集合钉在同一个适配器测试里。
 *       本类改为断言**传给端口的聚合**：{@code resourceId} 已写入而 {@code status} 仍是 CONFIRMED，
 *       以及「那一条 CAS 被调用过」——聚合不可变，状态不会被写路径顺手改掉是结构性事实。</li>
 * </ul>
 * 本类不再需要 MyBatis-Plus 的 {@code TableInfo} 初始化：应用层已经不构造任何 Wrapper。
 */
class ReservationApplicationServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Long STORE_ID = 1001L;
    /** 门店权威组织（organization_id = store.organization_id；禁止用 storeId 冒充）。 */
    private static final Long ORGANIZATION_ID = 3L;
    private static final Long ROOM_TYPE_ID = 55L;
    private static final Long RESERVATION_ID = 9L;
    /** 新格式单号（mock 生成器返回值）：{@code <前缀><yyyyMMdd><当日序号>}。 */
    private static final String RESERVATION_NO = "R202609190001";
    private static final String ORDER_NO = "O202609190001";
    /** 库里既有预约的号（读路径 fixture）。 */
    private static final String EXISTING_RESERVATION_NO = "R2026091800000000001";
    private static final OffsetDateTime START_AT = OffsetDateTime.parse("2026-09-18T20:00:00+08:00");
    private static final OffsetDateTime END_AT = OffsetDateTime.parse("2026-09-18T22:00:00+08:00");
    /** 门店本地墙上时间（+08:00）的时段，读路径 fixture 用。 */
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 9, 18, 20, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 9, 18, 22, 0);

    private ReservationRepository reservationRepository;
    private OrderRepository orderMapper;
    private KtvSessionApplicationService ktvSessionService;
    private ResourceStateClient resourceStateClient;
    private RoomTypeCatalogClient roomTypeCatalogClient;
    private TenantBusinessHoursClient businessHoursClient;
    private AuditClient auditClient;
    private DailySerialNumberGenerator dailySerialNumberGenerator;
    private ReservationApplicationService service;

    @BeforeEach
    void setUp() {
        reservationRepository = mock(ReservationRepository.class);
        orderMapper = mock(OrderRepository.class);
        ktvSessionService = mock(KtvSessionApplicationService.class);
        resourceStateClient = mock(ResourceStateClient.class);
        roomTypeCatalogClient = mock(RoomTypeCatalogClient.class);
        businessHoursClient = mock(TenantBusinessHoursClient.class);
        auditClient = mock(AuditClient.class);
        dailySerialNumberGenerator = mock(DailySerialNumberGenerator.class);
        service = new ReservationApplicationService(reservationRepository, orderMapper, ktvSessionService,
                resourceStateClient, roomTypeCatalogClient, businessHoursClient, auditClient,
                dailySerialNumberGenerator);
        // 门店作用域窄端口：openTable 转订单时取 organization_id/business_type 的权威快照。
        StoreScopeRepository storeScopeRepository = mock(StoreScopeRepository.class);
        when(storeScopeRepository.findActive(anyLong(), any())).thenReturn(Optional.of(
                new StoreScopeRepository.StoreScope(TENANT_ID, STORE_ID, ORGANIZATION_ID, "KTV", "ACTIVE")));
        service.setStoreScopeRepository(storeScopeRepository);
        // 单号生成器是外部依赖（真实实现走 ord_daily_serial 表）：这里按单据类型返回可断言的新格式号，
        // 生成规则本身由 DailySerialNumberGeneratorTest（H2 真跑）覆盖。
        when(dailySerialNumberGenerator.next(any(), any())).thenAnswer(invocation ->
                invocation.getArgument(0) == DailySerialNumberGenerator.DocType.ORDER
                        ? ORDER_NO
                        : RESERVATION_NO);
        // 默认「营业时间不可用」：不改变既有用例语义（跳过校验）。营业时间用例单独 stub。
        when(businessHoursClient.resolve(any())).thenReturn(Optional.empty());
        // 端口默认桩：新增回填主键与记账值；三个 CAS 默认都成功（失败路径单独 stub 成 false）。
        when(reservationRepository.insert(any(Reservation.class), any(WriteAudit.class))).thenAnswer(invocation -> {
            Reservation draft = invocation.getArgument(0);
            WriteAudit write = invocation.getArgument(1);
            return new Persisted<>(draft.withId(RESERVATION_ID),
                    new AuditSnapshot(write.operatorId(), write.at(), write.operatorId(), write.at()));
        });
        when(reservationRepository.updateStatusWithVersion(any(), anyInt(), any())).thenReturn(true);
        when(reservationRepository.updateAssignmentWithVersion(any(), anyInt(), any())).thenReturn(true);
        when(reservationRepository.updateScheduleWithVersion(any(), anyInt(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        CurrencyContextHolder.clear();
    }

    // ------------------------------------------------------------------ 创建：房型驱动

    /**
     * 营业时间（统一原则）：预约**到店时间**必须落在营业时段内。
     * 默认营业时间是 KTV 夜间业态的 18:00 – 次日 05:00，20:00 的预约正常放行。
     */
    @Test
    void createAllowsStartAtWithinBusinessHours() {
        givenRoomType();
        when(reservationRepository.countSlotHolding(any(), any(), any(), any(), any())).thenReturn(0L);
        givenBusinessHours("18:00", "05:00");

        ReservationView created = service.create(TENANT_ID, "idem-bh-ok", command(ROOM_TYPE_ID));

        assertEquals("PENDING", created.status());
    }

    /** 凌晨到店时间属于**前一个营业日**的时段（18:00–05:00 跨自然日），不能被当成越界。 */
    @Test
    void createAllowsEarlyMorningStartAtWhenHoursCrossMidnight() {
        givenRoomType();
        when(reservationRepository.countSlotHolding(any(), any(), any(), any(), any())).thenReturn(0L);
        givenBusinessHours("18:00", "05:00");
        OffsetDateTime startAt = OffsetDateTime.parse("2026-09-19T01:30:00+08:00");
        OffsetDateTime endAt = OffsetDateTime.parse("2026-09-19T04:30:00+08:00");

        ReservationView created = service.create(TENANT_ID, "idem-bh-midnight",
                new ReservationApplicationService.CreateReservationCommand(
                        "KTV", 99L, STORE_ID, ROOM_TYPE_ID, startAt, endAt, 4, "张三 13800138000"));

        assertEquals("PENDING", created.status());
    }

    /** 营业时间外（18:00–05:00 的 15:00）→ 422 RESERVATION_OUT_OF_BUSINESS_HOURS，且不落库。 */
    @Test
    void createRejectsStartAtOutsideBusinessHours() {
        givenBusinessHours("18:00", "05:00");
        OffsetDateTime startAt = OffsetDateTime.parse("2026-09-19T15:00:00+08:00");
        OffsetDateTime endAt = OffsetDateTime.parse("2026-09-19T18:00:00+08:00");

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(TENANT_ID, "idem-bh-bad",
                        new ReservationApplicationService.CreateReservationCommand(
                                "KTV", 99L, STORE_ID, ROOM_TYPE_ID, startAt, endAt, 4, "张三 13800138000")));

        assertEquals(422, ex.getStatus());
        assertEquals("RESERVATION_OUT_OF_BUSINESS_HOURS", ex.getCode());
        // 营业时间是本域能判定的规则，应在远程房型校验之前就拒绝（不浪费一次资源域调用）。
        verifyNoInteractions(roomTypeCatalogClient);
        verify(reservationRepository, never()).insert(any(Reservation.class), any(WriteAudit.class));
    }

    /** 租户服务读不到营业时间时**放行**（不阻断营业）：不抛异常、不误伤正常预约。 */
    @Test
    void createSkipsBusinessHoursCheckWhenConfigUnavailable() {
        givenRoomType();
        when(reservationRepository.countSlotHolding(any(), any(), any(), any(), any())).thenReturn(0L);
        when(businessHoursClient.resolve(any())).thenReturn(Optional.empty());
        OffsetDateTime startAt = OffsetDateTime.parse("2026-09-19T15:00:00+08:00");
        OffsetDateTime endAt = OffsetDateTime.parse("2026-09-19T18:00:00+08:00");

        ReservationView created = service.create(TENANT_ID, "idem-bh-unavailable",
                new ReservationApplicationService.CreateReservationCommand(
                        "KTV", 99L, STORE_ID, ROOM_TYPE_ID, startAt, endAt, 4, "张三 13800138000"));

        assertEquals("PENDING", created.status());
    }

    /**
     * 创建预约只写房型：resource_id 留空（到店再分配），响应带房型名/编码。
     *
     * <p>房型名/编码是**非列**展示字段：它们现在只存在于响应视图里（原持久化对象上是
     * {@code @TableField(exist = false)}，DDL 没有这两列），因此断言从「落库对象带这两个字段」
     * 改成「落库聚合只有房型 id」+「响应视图带房型名/编码」——前者由 {@link Reservation}
     * 没有这两个字段结构性保证。
     */
    @Test
    void createWritesRoomTypeAndLeavesResourceUnassigned() {
        givenRoomType();
        when(reservationRepository.countSlotHolding(any(), any(), any(), any(), any())).thenReturn(0L);

        ReservationView created = service.create(TENANT_ID, "idem-1", command(ROOM_TYPE_ID));

        Reservation inserted = capturedInsert();
        assertEquals(ROOM_TYPE_ID, inserted.roomTypeId());
        assertNull(inserted.resourceId(), "预约阶段不得写入具体包厢（到店才分配）");
        assertEquals(ReservationStatus.PENDING, inserted.status());
        assertEquals(START_AT.withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime(),
                inserted.startAt());
        assertEquals(ROOM_TYPE_ID, created.roomTypeId());
        assertEquals("VIP", created.roomTypeCode(), "房型编码是展示字段，随响应回填");
        assertEquals("VIP 大包", created.roomTypeName(), "房型名称是展示字段，随响应回填");
        assertNull(created.resourceName(), "未分配包厢时没有包厢名（到店后由分配包厢写入）");
        // 改造后创建路径不再校验「具体包厢可用」，因此完全不碰房态服务。
        verifyNoInteractions(resourceStateClient);
    }

    /** 缺少 roomTypeId：预约对象是房型，必填；400 ROOM_TYPE_REQUIRED。 */
    @Test
    void createRejectsMissingRoomType() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(TENANT_ID, null, command(null)));

        assertEquals(400, ex.getStatus());
        assertEquals("ROOM_TYPE_REQUIRED", ex.getCode());
        verifyNoInteractions(roomTypeCatalogClient);
    }

    /** 房型不存在或不属于该门店：400 ROOM_TYPE_INVALID。 */
    @Test
    void createRejectsUnknownRoomType() {
        when(roomTypeCatalogClient.roomType(STORE_ID, ROOM_TYPE_ID)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(TENANT_ID, null, command(ROOM_TYPE_ID)));

        assertEquals(400, ex.getStatus());
        assertEquals("ROOM_TYPE_INVALID", ex.getCode());
    }

    /** 房型被停用：400 ROOM_TYPE_DISABLED。 */
    @Test
    void createRejectsDisabledRoomType() {
        when(roomTypeCatalogClient.roomType(STORE_ID, ROOM_TYPE_ID)).thenReturn(Optional.of(
                new RoomTypeView(ROOM_TYPE_ID, "VIP", "VIP 大包", 12, 20000L, 6000L, "DISABLED")));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(TENANT_ID, null, command(ROOM_TYPE_ID)));

        assertEquals(400, ex.getStatus());
        assertEquals("ROOM_TYPE_DISABLED", ex.getCode());
    }

    /** 该房型没有任何启用包厢：409 ROOM_TYPE_NO_ROOM_AVAILABLE。 */
    @Test
    void createRejectsRoomTypeWithoutEnabledRoom() {
        when(roomTypeCatalogClient.roomType(STORE_ID, ROOM_TYPE_ID)).thenReturn(Optional.of(activeRoomType()));
        when(roomTypeCatalogClient.enabledRoomCount(STORE_ID, ROOM_TYPE_ID)).thenReturn(0L);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(TENANT_ID, null, command(ROOM_TYPE_ID)));

        assertEquals(409, ex.getStatus());
        assertEquals("ROOM_TYPE_NO_ROOM_AVAILABLE", ex.getCode());
    }

    /** 超订软保护：时段重叠的未取消预约数已到启用包厢数 → 409 ROOM_TYPE_FULL（资源域不可用则不降级）。 */
    @Test
    void createRejectsOverbookedRoomType() {
        givenRoomType();
        when(reservationRepository.countSlotHolding(any(), any(), any(), any(), any())).thenReturn(2L);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(TENANT_ID, null, command(ROOM_TYPE_ID)));

        assertEquals(409, ex.getStatus());
        assertEquals("ROOM_TYPE_FULL", ex.getCode());
        verify(reservationRepository, never()).insert(any(Reservation.class), any(WriteAudit.class));
    }

    /**
     * 超订计数的**定位口径**：端口方法名 {@code countSlotHolding} 表达「只数仍占名额的预约」，
     * 具体状态集合（PENDING/CONFIRMED/ARRIVED，绝不含 CONVERTED）由适配器固化并钉在
     * {@code ReservationRepositoryAdapterTest#countSlotHoldingCountsOnlySlotHoldingStatuses}。
     *
     * <p>回归背景不变：CONVERTED（已开台）若被算进名额，客人开台后包厢即便空闲也一直显示已满，
     * C 端约不出房（DEBUG 现场 3 间小包被 3 条 CONVERTED 预约锁死）。改造前这条断言读的是
     * {@code LambdaQueryWrapper} 的参数表；现在状态集合不再由应用层拼出来，所以断言改为
     * 「用本租户/门店/房型与该时段调用该端口」——应用层已经没有可以漏改状态的地方。
     */
    @Test
    void overbookingCountsThroughTheSlotHoldingPort() {
        givenRoomType();
        when(reservationRepository.countSlotHolding(any(), any(), any(), any(), any())).thenReturn(0L);

        service.create(TENANT_ID, null, command(ROOM_TYPE_ID));

        verify(reservationRepository).countSlotHolding(TENANT_ID, STORE_ID, ROOM_TYPE_ID,
                START_AT.withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime(),
                END_AT.withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime());
    }

    /** 预约数未到启用包厢数：放行。 */
    @Test
    void createAllowsWhenBelowCapacity() {
        givenRoomType();
        when(reservationRepository.countSlotHolding(any(), any(), any(), any(), any())).thenReturn(1L);

        ReservationView created = service.create(TENANT_ID, null, command(ROOM_TYPE_ID));

        assertEquals(ROOM_TYPE_ID, created.roomTypeId());
        verify(reservationRepository).insert(any(Reservation.class), any(WriteAudit.class));
    }

    /** 新单号规则：预约号由统一生成器分配（{@code R<yyyyMMdd><当日序号>}），不再用 R+UUID。 */
    @Test
    void createUsesDailySerialReservationNumber() {
        givenRoomType();
        when(reservationRepository.countSlotHolding(any(), any(), any(), any(), any())).thenReturn(0L);

        ReservationView created = service.create(TENANT_ID, "idem-no-1", command(ROOM_TYPE_ID));

        assertEquals(RESERVATION_NO, created.reservationNo());
        assertEquals(RESERVATION_NO, capturedInsert().reservationNo(), "落库的必须是生成器给的号");
        verify(dailySerialNumberGenerator).next(DailySerialNumberGenerator.DocType.RESERVATION, TENANT_ID);
    }

    /**
     * 幂等重试不换号：同 Idempotency-Key 命中已有预约时**直接返回原号且不再发号**
     * （生成器零交互 ⇒ 重试不会消耗/换掉当日序号）。
     */
    @Test
    void createReturnsExistingNumberOnIdempotentRetry() {
        Reservation existing = reservation(ReservationStatus.PENDING, null, ROOM_TYPE_ID);
        when(reservationRepository.findByIdempotencyKey(TENANT_ID, "idem-no-retry"))
                .thenReturn(Optional.of(persisted(existing)));

        ReservationView retried = service.create(TENANT_ID, "idem-no-retry", command(ROOM_TYPE_ID));

        assertEquals(EXISTING_RESERVATION_NO, retried.reservationNo());
        verifyNoInteractions(dailySerialNumberGenerator);
        verify(reservationRepository, never()).insert(any(Reservation.class), any(WriteAudit.class));
    }

    /** 序号服务不可用：失败关闭（503 DOC_NO_SEQUENCE_UNAVAILABLE），不降级、不落库一张号不合法/可能重的预约。 */
    @Test
    void createFailsClosedWhenSequenceUnavailable() {
        givenRoomType();
        when(reservationRepository.countSlotHolding(any(), any(), any(), any(), any())).thenReturn(0L);
        when(dailySerialNumberGenerator.next(any(), any()))
                .thenThrow(new BusinessException(DailySerialNumberGenerator.CODE_SEQUENCE_UNAVAILABLE,
                        "单号生成失败：序号服务不可用，请稍后重试"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(TENANT_ID, "idem-no-fail", command(ROOM_TYPE_ID)));

        assertEquals(DailySerialNumberGenerator.CODE_SEQUENCE_UNAVAILABLE, ex.getCode());
        verify(reservationRepository, never()).insert(any(Reservation.class), any(WriteAudit.class));
    }

    /** 资源域读不到房型：失败关闭（503 RESOURCE_STATE_UNAVAILABLE），绝不静默放行未校验的房型。 */
    @Test
    void createFailsClosedWhenCatalogUnavailable() {
        when(roomTypeCatalogClient.roomType(STORE_ID, ROOM_TYPE_ID))
                .thenThrow(new BusinessException("RESOURCE_STATE_UNAVAILABLE", "房型服务暂时不可用"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(TENANT_ID, null, command(ROOM_TYPE_ID)));

        assertEquals("RESOURCE_STATE_UNAVAILABLE", ex.getCode());
        verify(reservationRepository, never()).insert(any(Reservation.class), any(WriteAudit.class));
    }

    // ------------------------------------------------------------------ 到店分配包厢

    /**
     * 时段冲突（回归）：预约**不写资源占用**，因此房态永远看不出「这个时段已被别的预约锁了」。
     * 同一包厢、重叠时段的另一张未取消预约必须让分配失败（409 ROOM_RESERVED_OVERLAP）。
     */
    @Test
    void assignRoomRejectsRoomAlreadyReservedInSameWindow() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, null, ROOM_TYPE_ID));
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, ROOM_TYPE_ID, STORE_ID, "VIP 01"));
        when(resourceStateClient.requireState(3001L)).thenReturn(new ResourceStateClient.RoomState(
                true, "IDLE", null, "VIP 01"));
        when(reservationRepository.findRoomOverlap(STORE_ID, 3001L, RESERVATION_ID, WINDOW_START, WINDOW_END))
                .thenReturn(Optional.of(conflictingReservation(77L, "R2026091800000000099", 19, 0, 21, 0)));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals(409, ex.getStatus());
        assertEquals("ROOM_RESERVED_OVERLAP", ex.getCode());
        assertTrue(ex.getMessage().contains("R2026091800000000099"), ex.getMessage());
        assertTrue(ex.getMessage().contains("19:00"), ex.getMessage());
        verifyNoReservationWrites();
    }

    /**
     * 分配候选由服务端算：可分配性、原因（房态 / 本时段预约冲突）、当前已分配标记一次给全。
     * 这是「分配包厢没有做占用状态过滤」的修复口径 —— 前端不再自己合并资源列表。
     */
    @Test
    void assignableRoomsCarriesReasonsConflictsAndCurrentAssignment() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, 3001L, ROOM_TYPE_ID));
        when(resourceStateClient.roomStates("KTV_ROOM", STORE_ID)).thenReturn(List.of(
                new ResourceStateClient.RoomStateView(3001L, "VIP 01", "V3001", ROOM_TYPE_ID, "VIP 大包",
                        true, "IDLE", null),
                new ResourceStateClient.RoomStateView(3002L, "VIP 02", "V3002", ROOM_TYPE_ID, "VIP 大包",
                        true, "IDLE", null),
                new ResourceStateClient.RoomStateView(3003L, "VIP 03", "V3003", ROOM_TYPE_ID, "VIP 大包",
                        false, "OCCUPIED", "使用中"),
                new ResourceStateClient.RoomStateView(3004L, "VIP 04", "V3004", ROOM_TYPE_ID, "VIP 大包",
                        true, "IDLE", null),
                new ResourceStateClient.RoomStateView(3999L, "小包 01", "S01", 77L, "小包",
                        true, "IDLE", null)));
        when(reservationRepository.findRoomOverlaps(STORE_ID, RESERVATION_ID, WINDOW_START, WINDOW_END))
                .thenReturn(Map.of(3004L, conflictingReservation(88L, "R2026091800000000088", 20, 30, 23, 0, 3004L)));

        List<ReservationApplicationService.AssignableRoom> rooms = service.assignableRooms(9L);

        // 只回该房型的包厢（别的房型不进候选）
        assertEquals(4, rooms.size());
        ReservationApplicationService.AssignableRoom current = rooms.get(0);
        assertTrue(current.assignable());
        assertTrue(current.currentAssignment(), "当前已分配的包厢要能被识别");
        assertTrue(rooms.get(1).assignable());
        assertFalse(rooms.get(2).assignable());
        assertEquals("使用中", rooms.get(2).reason());
        ReservationApplicationService.AssignableRoom conflicted = rooms.get(3);
        assertFalse(conflicted.assignable(), "本时段已被其它预约占用的包厢不可分配");
        assertTrue(conflicted.reason().contains("R2026091800000000088"), conflicted.reason());
        assertEquals("R2026091800000000088", conflicted.conflictReservationNo());
    }

    /** 首次分配：写 resource_id，**状态保持 CONFIRMED**（锁房 ≠ 到店），审计 reservation.assign_room 带 before/after。 */
    @Test
    void assignRoomWritesResourceWithoutArriving() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, null, ROOM_TYPE_ID));
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, ROOM_TYPE_ID, STORE_ID, "VIP 01"));
        when(resourceStateClient.requireState(3001L)).thenReturn(new ResourceStateClient.RoomState(
                true, "IDLE", null, "VIP 01"));

        ReservationView assigned = service.assignRoom(9L, 3001L, false);

        assertEquals(3001L, assigned.resourceId());
        assertEquals("CONFIRMED", assigned.status(), "提前分配包厢不得把预约改成「客户已到店」");
        assertNull(assigned.arrivedAt(), "分配包厢不写真实到店时间");
        assertEquals("VIP 01", assigned.resourceName());
        assertAssignmentWriteCarriesResourceWithoutStatus();
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("reservation.assign_room", record.action());
        assertTrue(record.detailJson().contains("\"before\":{\"resourceId\":null"),
                record.detailJson());
        assertTrue(record.detailJson().contains("\"after\":{\"resourceId\":3001,\"resourceName\":\"VIP 01\"}"),
                record.detailJson());
    }

    /** 未确认（PENDING）预约也可提前锁房：状态仍是 PENDING，不会被「分配」顺手确认到店。 */
    @Test
    void assignRoomKeepsPendingStatusForUnconfirmedReservation() {
        givenReservation(reservation(ReservationStatus.PENDING, null, ROOM_TYPE_ID));
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, ROOM_TYPE_ID, STORE_ID, "VIP 01"));
        when(resourceStateClient.requireState(3001L)).thenReturn(new ResourceStateClient.RoomState(
                true, "IDLE", null, "VIP 01"));

        ReservationView assigned = service.assignRoom(9L, 3001L, false);

        assertEquals(3001L, assigned.resourceId());
        assertEquals("PENDING", assigned.status());
    }

    /** 已分配同一包厢：幂等成功，不写库、不重复留痕。 */
    @Test
    void assignRoomIsIdempotentForSameRoom() {
        givenReservation(reservation(ReservationStatus.ARRIVED, 3001L, ROOM_TYPE_ID));

        ReservationView assigned = service.assignRoom(9L, 3001L, false);

        assertEquals(3001L, assigned.resourceId());
        verifyNoReservationWrites();
        verify(resourceStateClient, never()).requireRoom(anyLong());
        verifyNoInteractions(auditClient);
    }

    /** 换包厢未显式覆盖：409 RESERVATION_ROOM_ASSIGNED（避免误改派）。 */
    @Test
    void assignRoomRejectsChangeWithoutOverride() {
        givenReservation(reservation(ReservationStatus.ARRIVED, 3001L, ROOM_TYPE_ID));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3002L, false));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_ROOM_ASSIGNED", ex.getCode());
        verifyNoReservationWrites();
    }

    /**
     * 换包厢显式覆盖：成功且审计 before/after 都带上。
     *
     * <p>断言变更说明：{@code before.resourceName} 现在**结构性为 null**。包厢名不是列
     * （原持久化对象上 {@code @TableField(exist = false)}），{@code findById}（原 {@code selectById}）
     * 从来读不到它——旧断言里的「VIP 01」是 fixture 手工塞进持久化对象才出现的，生产路径拿不到。
     * 领域模型不含非列字段后，审计的 before 只保留真实列 {@code resource_id}，
     * 因此这里改为断言 before.resourceId 正确、且名字如实为 null（不假装有值）。
     */
    @Test
    void assignRoomOverridesWithTrail() {
        givenReservation(reservation(ReservationStatus.ARRIVED, 3001L, ROOM_TYPE_ID));
        when(resourceStateClient.requireRoom(3002L)).thenReturn(roomSnapshot(3002L, ROOM_TYPE_ID, STORE_ID, "VIP 02"));
        when(resourceStateClient.requireState(3002L)).thenReturn(new ResourceStateClient.RoomState(
                true, "IDLE", null, "VIP 02"));

        ReservationView assigned = service.assignRoom(9L, 3002L, true);

        assertEquals(3002L, assigned.resourceId());
        AuditClient.AuditRecord record = capturedAudit();
        assertTrue(record.detailJson().contains("\"before\":{\"resourceId\":3001,\"resourceName\":null}"),
                record.detailJson());
        assertTrue(record.detailJson().contains("\"after\":{\"resourceId\":3002,\"resourceName\":\"VIP 02\"}"),
                record.detailJson());
        assertTrue(record.detailJson().contains("\"override\":true"), record.detailJson());
    }

    /** 包厢房型与预约房型不一致：400 ROOM_TYPE_MISMATCH。 */
    @Test
    void assignRoomRejectsRoomOfAnotherRoomType() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, null, ROOM_TYPE_ID));
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, 77L, STORE_ID, "小包 01"));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals(400, ex.getStatus());
        assertEquals("ROOM_TYPE_MISMATCH", ex.getCode());
    }

    /** 包厢不属于预约门店：400 ROOM_STORE_MISMATCH。 */
    @Test
    void assignRoomRejectsRoomOfAnotherStore() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, null, ROOM_TYPE_ID));
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, ROOM_TYPE_ID, 2002L, "VIP 01"));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals(400, ex.getStatus());
        assertEquals("ROOM_STORE_MISMATCH", ex.getCode());
    }

    /** 包厢使用中/清洁中：409 ROOM_UNAVAILABLE（当前不可分配）。 */
    @Test
    void assignRoomRejectsUnavailableRoom() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, null, ROOM_TYPE_ID));
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, ROOM_TYPE_ID, STORE_ID, "VIP 01"));
        when(resourceStateClient.requireState(3001L)).thenReturn(new ResourceStateClient.RoomState(
                false, "OCCUPIED", "使用中", "VIP 01"));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals(409, ex.getStatus());
        assertEquals("ROOM_UNAVAILABLE", ex.getCode());
        verifyNoReservationWrites();
    }

    /** 房态服务不可达：fail-closed（503），不允许「拿不到房态也把包厢分出去」。 */
    @Test
    void assignRoomFailsClosedWhenResourceUnavailable() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, null, ROOM_TYPE_ID));
        when(resourceStateClient.requireRoom(3001L))
                .thenThrow(new BusinessException("RESOURCE_STATE_UNAVAILABLE", "房态服务暂时不可用"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals("RESOURCE_STATE_UNAVAILABLE", ex.getCode());
        verifyNoReservationWrites();
    }

    /** 历史预约没有房型：409 RESERVATION_ROOM_TYPE_REQUIRED（不给旧数据隐式改派能力）。 */
    @Test
    void assignRoomRejectsLegacyReservationWithoutRoomType() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, 3001L, null));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3002L, true));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_ROOM_TYPE_REQUIRED", ex.getCode());
        verify(resourceStateClient, never()).requireRoom(anyLong());
    }

    /** 已取消/已开台：409 RESERVATION_STATUS_INVALID。 */
    @Test
    void assignRoomRejectsCancelledReservation() {
        givenReservation(reservation(ReservationStatus.CANCELLED, null, ROOM_TYPE_ID));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
    }

    /** 缺少 resourceId：400 RESOURCE_ID_REQUIRED。 */
    @Test
    void assignRoomRequiresResourceId() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, null, ROOM_TYPE_ID));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, null, false));

        assertEquals(400, ex.getStatus());
        assertEquals("RESOURCE_ID_REQUIRED", ex.getCode());
    }

    // ------------------------------------------------------------------ 开台前置

    /** 未分配包厢就开台：409 RESERVATION_ROOM_NOT_ASSIGNED。 */
    @Test
    void openTableRequiresAssignedRoom() {
        givenReservation(reservation(ReservationStatus.ARRIVED, null, ROOM_TYPE_ID));

        ApiException ex = assertThrows(ApiException.class, () -> service.openTable(9L, null));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_ROOM_NOT_ASSIGNED", ex.getCode());
        verify(orderMapper, never()).insert(any(Order.class), any());
    }

    /** 旧数据兼容：只有 resource_id 的历史预约仍可开台，用既有包厢建会话，币种取当前上下文（不再硬编码 CNY）。 */
    @Test
    void openTableStillWorksForLegacyReservationWithOnlyResourceId() {
        givenReservation(reservation(ReservationStatus.ARRIVED, 3001L, null));
        when(orderMapper.insert(any(Order.class), any()))
                .thenAnswer(invocation -> ((Order) invocation.getArgument(0)).withId(88L));
        when(ktvSessionService.create(eq(TENANT_ID), eq(88L), eq(3001L))).thenReturn(session(555L));
        when(orderMapper.findById(88L)).thenReturn(Optional.of(order(88L, "DRAFT")));
        CurrencyContextHolder.set(Currency.CNY);

        OrderView opened = service.openTable(9L, null);

        assertEquals(555L, opened.sessionId());
        verify(ktvSessionService).create(TENANT_ID, 88L, 3001L);
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(orderCaptor.capture(), any());
        assertEquals("CNY", orderCaptor.getValue().currencyCode(),
                "开台订单币种取 CurrencyResolver（当前上下文 CNY），不是硬编码常量");
    }

    /** 没有币种上下文时回退 USD（老 token 兼容），绝不因缺 claim 失败。 */
    @Test
    void openTableFallsBackToDefaultCurrencyWithoutContext() {
        givenReservation(reservation(ReservationStatus.ARRIVED, 3001L, ROOM_TYPE_ID));
        when(orderMapper.insert(any(Order.class), any()))
                .thenAnswer(invocation -> ((Order) invocation.getArgument(0)).withId(88L));
        when(ktvSessionService.create(any(), any(), any())).thenReturn(session(555L));
        when(orderMapper.findById(88L)).thenReturn(Optional.of(order(88L, "DRAFT")));

        service.openTable(9L, null);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(orderCaptor.capture(), any());
        assertEquals("USD", orderCaptor.getValue().currencyCode());
    }

    // ------------------------------------------------------------------ 取消预约（运营代客取消）

    /** 原因必填：空白 → 400 CANCEL_REASON_REQUIRED，且不改库、不留痕。 */
    @Test
    void cancelRequiresReason() {
        givenReservation(reservation(ReservationStatus.PENDING, null, ROOM_TYPE_ID));

        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(9L, "   "));

        assertEquals(400, ex.getStatus());
        assertEquals("CANCEL_REASON_REQUIRED", ex.getCode());
        assertEquals("取消预约必须填写原因", ex.getMessage());
        verifyNoReservationWrites();
        verifyNoInteractions(auditClient);
    }

    /** 原因超长：400 CANCEL_REASON_TOO_LONG（与其它自由文本同为 255 上限）。 */
    @Test
    void cancelRejectsTooLongReason() {
        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(9L, "原".repeat(256)));

        assertEquals(400, ex.getStatus());
        assertEquals("CANCEL_REASON_TOO_LONG", ex.getCode());
        verifyNoInteractions(reservationRepository);
    }

    /** PENDING 预约可取消：状态 → CANCELLED，审计 reservation.cancel 带前后状态/原因/预约号/房型。 */
    @Test
    void cancelPendingReservationWritesAuditWithBeforeAfterAndReason() {
        givenReservation(reservation(ReservationStatus.PENDING, null, ROOM_TYPE_ID));

        ReservationView cancelled = service.cancel(9L, " 客人临时有事 ");

        assertEquals("CANCELLED", cancelled.status());
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("reservation.cancel", record.action());
        assertEquals("预约取消", record.actionLabel());
        assertEquals("reservation", record.resourceType());
        assertEquals("9", record.resourceId());
        assertEquals(EXISTING_RESERVATION_NO, record.resourceName());
        assertEquals("reservation-cancel:9", record.idempotencyKey());
        assertTrue(record.detailJson().contains("\"beforeStatus\":\"PENDING\""), record.detailJson());
        assertTrue(record.detailJson().contains("\"afterStatus\":\"CANCELLED\""), record.detailJson());
        assertTrue(record.detailJson().contains("\"reason\":\"客人临时有事\""), record.detailJson());
        assertTrue(record.detailJson().contains("\"reservationNo\":\"R2026091800000000001\""), record.detailJson());
        assertTrue(record.detailJson().contains("\"roomTypeId\":55"), record.detailJson());
    }

    /** CONFIRMED 预约同样可取消，beforeStatus 如实记录。 */
    @Test
    void cancelConfirmedReservationSucceeds() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, null, ROOM_TYPE_ID));

        ReservationView cancelled = service.cancel(9L, "客人要求取消");

        assertEquals("CANCELLED", cancelled.status());
        AuditClient.AuditRecord record = capturedAudit();
        assertTrue(record.detailJson().contains("\"beforeStatus\":\"CONFIRMED\""), record.detailJson());
    }

    /** 已到店（ARRIVED）：409 RESERVATION_STATUS_INVALID，提示改走「取消订单」，失败留痕 FAILURE。 */
    @Test
    void cancelRejectsArrivedReservation() {
        givenReservation(reservation(ReservationStatus.ARRIVED, 3001L, ROOM_TYPE_ID));

        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(9L, "客人要求取消"));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
        assertTrue(ex.getMessage().contains("取消订单"), ex.getMessage());
        verifyNoReservationWrites();
        AuditClient.AuditRecord failure = capturedFailure();
        assertEquals("reservation.cancel", failure.action());
        assertEquals("9", failure.resourceId());
        assertEquals("RESERVATION_STATUS_INVALID", failure.errorCode());
        // 预约 contact（姓名/手机号）绝不进审计详情，也不带幂等键（重复失败各自留痕）。
        assertNull(failure.idempotencyKey());
        assertFalse(failure.detailJson().contains("13800138000"), failure.detailJson());
    }

    /** 已开台（order_id 非空）：即便状态不是 ARRIVED 也拒绝取消预约，避免「预约取消了、订单还在计时」。 */
    @Test
    void cancelRejectsOpenedReservationWithOrderId() {
        givenReservation(reservation(ReservationStatus.CONVERTED, 3001L, ROOM_TYPE_ID, 88L, null));

        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(9L, "客人要求取消"));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
        assertTrue(ex.getMessage().contains("取消订单"), ex.getMessage());
    }

    /** 已取消：409（不重复取消），且失败留痕 FAILURE（不覆盖成功路径的幂等键）。 */
    @Test
    void cancelRejectsAlreadyCancelledReservation() {
        givenReservation(reservation(ReservationStatus.CANCELLED, null, ROOM_TYPE_ID));

        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(9L, "客人要求取消"));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
        assertTrue(ex.getMessage().contains("已取消"), ex.getMessage());
        AuditClient.AuditRecord failure = capturedFailure();
        assertEquals("reservation.cancel", failure.action());
        assertEquals("RESERVATION_STATUS_INVALID", failure.errorCode());
        assertNull(failure.idempotencyKey());
    }

    // ------------------------------------------------------------------ 旧数据读取兼容

    /** 列表回填：历史预约（只有 resource_id）显示旧包厢名；新预约显示房型名/编码。 */
    @Test
    void listFallsBackToLegacyRoomForOldRows() {
        Reservation legacy = reservation(ReservationStatus.ARRIVED, 3001L, null);
        Reservation byRoomType = reservation(ReservationStatus.PENDING, null, ROOM_TYPE_ID);
        when(reservationRepository.findByStartAtRange(null, null))
                .thenReturn(List.of(persisted(legacy), persisted(byRoomType)));
        when(roomTypeCatalogClient.roomTypes(STORE_ID)).thenReturn(List.of(activeRoomType()));
        when(roomTypeCatalogClient.rooms(STORE_ID)).thenReturn(List.of(
                new RoomView(3001L, "VIP 01", "V001", ROOM_TYPE_ID, "ENABLED", 12)));

        List<ReservationView> rows = service.list(TimeRange.none());

        assertEquals("VIP 01", rows.get(0).resourceName(), "历史预约回退展示旧包厢");
        assertNull(rows.get(0).roomTypeName());
        assertEquals("VIP 大包", rows.get(1).roomTypeName());
        assertEquals("VIP", rows.get(1).roomTypeCode());
        assertNull(rows.get(1).resourceName(), "未分配包厢时没有包厢名，前端展示「到店后分配」");
    }

    /**
     * C 端「我的预约」：同一展示装配口径（按会员查询 + 回填房型名/包厢名）。
     * 会员条件在端口方法名上表达（{@code findMine}），应用层不再自己拼客户条件。
     */
    @Test
    void listMineEnrichesThroughTheCustomerPort() {
        Reservation po = reservation(ReservationStatus.PENDING, null, ROOM_TYPE_ID);
        when(reservationRepository.findMine(99L)).thenReturn(List.of(persisted(po)));
        when(roomTypeCatalogClient.roomTypes(STORE_ID)).thenReturn(List.of(activeRoomType()));

        List<ReservationView> rows = service.listMine(99L);

        assertEquals(1, rows.size());
        assertEquals("VIP 大包", rows.get(0).roomTypeName());
        verify(reservationRepository).findMine(99L);
    }

    /** 资源域读不到时只丢展示字段，列表本身照常返回（读路径降级）。 */
    @Test
    void listDegradesWhenCatalogUnavailable() {
        Reservation po = reservation(ReservationStatus.PENDING, null, ROOM_TYPE_ID);
        when(reservationRepository.findByStartAtRange(null, null)).thenReturn(List.of(persisted(po)));
        when(roomTypeCatalogClient.roomTypes(STORE_ID)).thenThrow(new BusinessException("RESOURCE_STATE_UNAVAILABLE", "x"));

        List<ReservationView> rows = service.list(TimeRange.none());

        assertEquals(1, rows.size());
        assertNull(rows.get(0).roomTypeName());
    }

    // ------------------------------------------------------------------ 确认/到店/分配/开台留痕

    /** 确认预约：状态 → CONFIRMED 且写 SUCCEEDED 留痕（此前确认成功完全没有审计）。 */
    @Test
    void confirmWritesSucceededAudit() {
        givenReservation(reservation(ReservationStatus.PENDING, null, ROOM_TYPE_ID));

        ReservationView confirmed = service.confirm(9L, 0);

        assertEquals("CONFIRMED", confirmed.status());
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("reservation.confirm", record.action());
        assertEquals("预约确认", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertNull(record.errorCode());
    }

    /**
     * CAS 判定点（新增覆盖）：端口条件更新影响 0 行 = 期间已被他人写入 → 抛
     * RESERVATION_VERSION_CONFLICT 并留失败痕迹。
     *
     * <p>改造前这条只由「应用层比出来的版本不一致」覆盖；真正的并发保护是适配器那条
     * {@code WHERE version = ?}，因此这里补上「CAS 返回 false」这一路。
     * 同一条 SQL 的存在性由 {@code ReservationRepositoryAdapterTest} 钉住。
     */
    @Test
    void confirmRaisesConflictWhenConditionalUpdateAffectsNoRow() {
        givenReservation(reservation(ReservationStatus.PENDING, null, ROOM_TYPE_ID));
        when(reservationRepository.updateStatusWithVersion(any(), anyInt(), any())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.confirm(9L, 0));

        assertEquals("RESERVATION_VERSION_CONFLICT", ex.getCode());
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.confirm", record.action());
        assertEquals("RESERVATION_VERSION_CONFLICT", record.errorCode());
        assertNull(record.idempotencyKey());
    }

    /** 状态不符（已到店）时确认失败：FAILURE + 稳定业务码，且不带幂等键。 */
    @Test
    void confirmWritesFailureAuditWithStableErrorCode() {
        givenReservation(reservation(ReservationStatus.ARRIVED, 3001L, ROOM_TYPE_ID));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.confirm(9L, 0));

        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.confirm", record.action());
        assertEquals("RESERVATION_STATUS_INVALID", record.errorCode());
        assertEquals(String.valueOf(9L), record.resourceId());
        assertNull(record.idempotencyKey());
    }

    /** 乐观锁版本冲突同样留失败痕迹（版本号来自调用方提交）。 */
    @Test
    void confirmWritesFailureAuditOnVersionConflict() {
        givenReservation(reservation(ReservationStatus.PENDING, null, ROOM_TYPE_ID));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.confirm(9L, 7));

        assertEquals("RESERVATION_VERSION_CONFLICT", ex.getCode());
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.confirm", record.action());
        assertEquals("RESERVATION_VERSION_CONFLICT", record.errorCode());
    }

    /** 到店：CONFIRMED → ARRIVED 且写 SUCCEEDED 留痕。 */
    @Test
    void arrivalWritesSucceededAudit() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, null, ROOM_TYPE_ID));

        ReservationView arrived = service.arrival(9L, "客人已到");

        assertEquals("ARRIVED", arrived.status());
        assertNotNull(arrived.arrivedAt(), "到店登记必须写真实到店时间（arrived_at）");
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("reservation.arrival", record.action());
        assertEquals("预约到店", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    }

    /** 未确认（PENDING）不能直接到店：FAILURE + RESERVATION_STATUS_INVALID。 */
    @Test
    void arrivalWritesFailureAuditWithStableErrorCode() {
        givenReservation(reservation(ReservationStatus.PENDING, null, ROOM_TYPE_ID));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.arrival(9L, "客人已到"));

        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.arrival", record.action());
        assertEquals("RESERVATION_STATUS_INVALID", record.errorCode());
    }

    /** 分配包厢失败（包厢使用中）：FAILURE + ROOM_UNAVAILABLE。 */
    @Test
    void assignRoomWritesFailureAuditWithStableErrorCode() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, null, ROOM_TYPE_ID));
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, ROOM_TYPE_ID, STORE_ID, "VIP 01"));
        when(resourceStateClient.requireState(3001L))
                .thenReturn(new ResourceStateClient.RoomState(false, "OCCUPIED", "使用中", "VIP 01"));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals("ROOM_UNAVAILABLE", ex.getCode());
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.assign_room", record.action());
        assertEquals("ROOM_UNAVAILABLE", record.errorCode());
        assertNull(record.idempotencyKey());
    }

    /** 已分配其它包厢且未显式覆盖：FAILURE + RESERVATION_ROOM_ASSIGNED（不写库、不改派）。 */
    @Test
    void assignRoomWritesFailureAuditWhenAlreadyAssigned() {
        givenReservation(reservation(ReservationStatus.ARRIVED, 3001L, ROOM_TYPE_ID));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3002L, false));

        assertEquals("RESERVATION_ROOM_ASSIGNED", ex.getCode());
        verifyNoReservationWrites();
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.assign_room", record.action());
        assertEquals("RESERVATION_ROOM_ASSIGNED", record.errorCode());
    }

    /** 预约开台：生成订单 + 会话并写 SUCCEEDED 留痕（此前开台完全没有审计）。 */
    @Test
    void openTableWritesSucceededAudit() {
        givenReservation(reservation(ReservationStatus.ARRIVED, 3001L, ROOM_TYPE_ID));
        when(orderMapper.insert(any(Order.class), any()))
                .thenAnswer(invocation -> ((Order) invocation.getArgument(0)).withId(88L));
        when(ktvSessionService.create(any(), any(), any())).thenReturn(session(555L));
        when(orderMapper.findById(88L)).thenReturn(Optional.of(order(88L, "DRAFT")));

        OrderView order = service.openTable(9L, null);

        assertEquals(555L, order.sessionId());
        assertEquals(ReservationStatus.CONVERTED, capturedStatusWrite().status());
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("reservation.open_table", record.action());
        assertEquals("预约开台", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertTrue(record.detailJson().contains("\"orderId\":88"), record.detailJson());
    }

    /** 预约开台产生的订单同样走统一生成器：{@code O<yyyyMMdd><当日序号>}（不能漏掉这条建单入口）。 */
    @Test
    void openTableUsesDailySerialOrderNumber() {
        givenReservation(reservation(ReservationStatus.ARRIVED, 3001L, ROOM_TYPE_ID));
        when(orderMapper.insert(any(Order.class), any()))
                .thenAnswer(invocation -> ((Order) invocation.getArgument(0)).withId(88L));
        when(ktvSessionService.create(any(), any(), any())).thenReturn(session(555L));
        when(orderMapper.findById(88L)).thenReturn(Optional.of(order(88L, "DRAFT")));

        service.openTable(9L, null);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(captor.capture(), any());
        assertEquals(ORDER_NO, captor.getValue().orderNo(), "开台订单号必须来自统一生成器");
        assertEquals(ORGANIZATION_ID, captor.getValue().organizationId(),
                "订单组织必须来自门店权威快照（organization_id = store.organization_id，禁止 storeId 兜底）");
        assertEquals("KTV", captor.getValue().businessType(), "订单业态取门店权威业态");
        verify(dailySerialNumberGenerator).next(DailySerialNumberGenerator.DocType.ORDER, TENANT_ID);
    }

    /** 未确认（PENDING）的预约不能开台：FAILURE + RESERVATION_STATUS_INVALID（先确认再开台）。 */
    @Test
    void openTableWritesFailureAuditWhenNotConfirmed() {
        givenReservation(reservation(ReservationStatus.PENDING, 3001L, ROOM_TYPE_ID));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.openTable(9L, null));

        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.open_table", record.action());
        assertEquals("RESERVATION_STATUS_INVALID", record.errorCode());
        assertNull(record.idempotencyKey());
    }

    /** 已确认（CONFIRMED）但未登记到店也可直接开台：隐含登记到店时间，审计标 arrivalImplied=true。 */
    @Test
    void openTableFromConfirmedImpliesArrival() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, 3001L, ROOM_TYPE_ID));
        when(orderMapper.insert(any(Order.class), any()))
                .thenAnswer(invocation -> ((Order) invocation.getArgument(0)).withId(88L));
        when(ktvSessionService.create(any(), any(), any())).thenReturn(session(555L));
        when(orderMapper.findById(88L)).thenReturn(Optional.of(order(88L, "DRAFT")));

        service.openTable(9L, null);

        Reservation write = capturedStatusWrite();
        assertEquals(ReservationStatus.CONVERTED, write.status());
        assertNotNull(write.arrivedAt(), "CONFIRMED 直接开台必须隐含登记到店时间");
        AuditClient.AuditRecord record = capturedAudit();
        assertTrue(record.detailJson().contains("\"arrivalImplied\":true"), record.detailJson());
    }

    /** 重复开台：已回填 order_id（状态已是 CONVERTED）时返回既有订单，不重复建单/建会话。 */
    @Test
    void openTableIsIdempotentForConvertedReservation() {
        givenReservation(reservation(ReservationStatus.CONVERTED, 3001L, ROOM_TYPE_ID, 88L, null));
        Order existing = order(88L, "DRAFT");
        when(orderMapper.findById(88L)).thenReturn(Optional.of(existing));
        when(ktvSessionService.findByOrderId(88L)).thenReturn(session(555L));

        OrderView opened = service.openTable(9L, null);

        assertEquals(88L, opened.id());
        assertEquals(555L, opened.sessionId());
        verify(orderMapper, never()).insert(any(Order.class), any());
        verifyNoInteractions(auditClient);
    }

    /** 已到店但未分配包厢：FAILURE + RESERVATION_ROOM_NOT_ASSIGNED。 */
    @Test
    void openTableWritesFailureAuditWhenRoomNotAssigned() {
        givenReservation(reservation(ReservationStatus.ARRIVED, null, ROOM_TYPE_ID));

        ApiException ex = assertThrows(ApiException.class, () -> service.openTable(9L, null));

        assertEquals("RESERVATION_ROOM_NOT_ASSIGNED", ex.getCode());
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.open_table", record.action());
        assertEquals("RESERVATION_ROOM_NOT_ASSIGNED", record.errorCode());
    }

    // ------------------------------------------------------------------ 未到店（NO_SHOW）

    /** 已过预约开始时间仍未到店：CONFIRMED → NO_SHOW 并留痕（此前 NO_SHOW 没有任何写入点）。 */
    @Test
    void noShowMarksConfirmedReservationAfterStartTime() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, 3001L, ROOM_TYPE_ID,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)));

        ReservationView noShow = service.noShow(9L);

        assertEquals("NO_SHOW", noShow.status());
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("reservation.no_show", record.action());
        assertEquals("预约未到店", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertTrue(record.detailJson().contains("\"beforeStatus\":\"CONFIRMED\""), record.detailJson());
    }

    /** 预约开始时间还没到：409 RESERVATION_NOT_STARTED（没到点谈不上未到店），且不写库。 */
    @Test
    void noShowRejectsBeforeStartTime() {
        givenReservation(reservation(ReservationStatus.CONFIRMED, 3001L, ROOM_TYPE_ID,
                LocalDateTime.now().plusHours(3), LocalDateTime.now().plusHours(4)));

        ApiException ex = assertThrows(ApiException.class, () -> service.noShow(9L));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_NOT_STARTED", ex.getCode());
        verifyNoReservationWrites();
    }

    /** 已到店/已开台不能标未到店：409 RESERVATION_STATUS_INVALID（走取消订单）。 */
    @Test
    void noShowRejectsArrivedReservation() {
        givenReservation(reservation(ReservationStatus.ARRIVED, 3001L, ROOM_TYPE_ID,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)));

        ApiException ex = assertThrows(ApiException.class, () -> service.noShow(9L));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.no_show", record.action());
    }

    // ------------------------------------------------------------------ fixtures / helpers

    private void givenRoomType() {
        when(roomTypeCatalogClient.roomType(STORE_ID, ROOM_TYPE_ID)).thenReturn(Optional.of(activeRoomType()));
        when(roomTypeCatalogClient.enabledRoomCount(STORE_ID, ROOM_TYPE_ID)).thenReturn(2L);
    }

    /** 给定生效营业时间（走真实 client 的 BusinessHours 语义，不 mock 判断逻辑本身）。 */
    private void givenBusinessHours(String open, String close) {
        when(businessHoursClient.resolve(any())).thenReturn(Optional.of(
                new TenantBusinessHoursClient.BusinessHours(java.time.LocalTime.parse(open),
                        java.time.LocalTime.parse(close), "TENANT")));
    }

    private void givenReservation(Reservation reservation) {
        when(reservationRepository.findById(reservation.id())).thenReturn(Optional.of(persisted(reservation)));
    }

    private static RoomTypeView activeRoomType() {
        return new RoomTypeView(ROOM_TYPE_ID, "VIP", "VIP 大包", 12, 20000L, 6000L, "ACTIVE");
    }

    private static ResourceStateClient.RoomSnapshot roomSnapshot(Long resourceId, Long roomTypeId, Long storeId,
                                                                String name) {
        return new ResourceStateClient.RoomSnapshot(name, "V" + resourceId, 12, "VIP", "VIP 大包", 20000L, 6000L,
                resourceId, roomTypeId, storeId, "ENABLED");
    }

    private static ReservationApplicationService.CreateReservationCommand command(Long roomTypeId) {
        return new ReservationApplicationService.CreateReservationCommand(
                "KTV", 99L, STORE_ID, roomTypeId, START_AT, END_AT, 4, "张三 13800138000");
    }

    /** 会话是 KTV 子域的不可变记录：本测试只消费它的标识（KTV 侧细节由 KTV 测试覆盖）。 */
    private static KtvSession session(Long id) {
        return KtvSession.reserved(TENANT_ID, STORE_ID, "KTV", 88L, 3001L, "VIP 01", "V3001").withId(id);
    }

    /** 订单夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。 */
    private static Order order(Long id, String status) {
        return new Order(id, TENANT_ID, null, STORE_ID, "ORD-20260918-0001", null, "KTV", null, status, RefundStatus.NONE.name(), "CNY",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, 0);
    }

    /** 库里既有预约（读路径 fixture）：审计快照固定，便于断言响应里的记账值。 */
    private static Persisted<Reservation> persisted(Reservation reservation) {
        return new Persisted<>(reservation, new AuditSnapshot(1L, LocalDateTime.of(2026, 9, 18, 9, 0),
                1L, LocalDateTime.of(2026, 9, 18, 9, 30)));
    }

    private static Reservation reservation(ReservationStatus status, Long resourceId, Long roomTypeId) {
        return reservation(status, resourceId, roomTypeId, WINDOW_START, WINDOW_END);
    }

    private static Reservation reservation(ReservationStatus status, Long resourceId, Long roomTypeId,
                                           LocalDateTime startAt, LocalDateTime endAt) {
        return reservation(status, resourceId, roomTypeId, startAt, endAt, null, null);
    }

    /** 完整 fixture：订单回填与到店时间可按用例给（历史预约/已开台/到店登记各不相同）。 */
    private static Reservation reservation(ReservationStatus status, Long resourceId, Long roomTypeId,
                                           Long orderId, LocalDateTime arrivedAt) {
        return reservation(status, resourceId, roomTypeId, WINDOW_START, WINDOW_END, orderId, arrivedAt);
    }

    private static Reservation reservation(ReservationStatus status, Long resourceId, Long roomTypeId,
                                           LocalDateTime startAt, LocalDateTime endAt,
                                           Long orderId, LocalDateTime arrivedAt) {
        return new Reservation(RESERVATION_ID, TENANT_ID, STORE_ID, EXISTING_RESERVATION_NO, null, 99L, "KTV",
                resourceId, roomTypeId, startAt, endAt, 4, "张三 13800138000", status, orderId, arrivedAt, 0);
    }

    /**
     * 分配包厢的端口调用：**只带 resource_id，不带新状态**（聚合不可变，status 仍是 CONFIRMED）。
     *
     * <p>原断言读的是 {@code LambdaUpdateWrapper#getSqlSet()}（SQL 形状），现在列集合是
     * {@code updateAssignmentWithVersion} 的端口契约，SQL 形状钉在
     * {@code ReservationRepositoryAdapterTest#updateAssignmentWritesResourceIdAndNeverStatus}；
     * 这里断言应用层交给端口的事实：包厢已写入、状态没被顺手改掉。
     */
    private void assertAssignmentWriteCarriesResourceWithoutStatus() {
        Reservation written = capturedAssignmentWrite();
        assertEquals(3001L, written.resourceId());
        assertEquals(ReservationStatus.CONFIRMED, written.status(),
                "分配包厢不得顺手改状态（状态流转只走 updateStatusWithVersion）");
        assertNull(written.arrivedAt(), "分配包厢不写真实到店时间");
    }

    /** 三个 CAS 写路径都没被调用（失败/幂等路径不得写库）。 */
    private void verifyNoReservationWrites() {
        verify(reservationRepository, never()).updateStatusWithVersion(any(), anyInt(), any());
        verify(reservationRepository, never()).updateAssignmentWithVersion(any(), anyInt(), any());
        verify(reservationRepository, never()).updateScheduleWithVersion(any(), anyInt(), any());
    }

    private Reservation capturedInsert() {
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).insert(captor.capture(), any(WriteAudit.class));
        return captor.getValue();
    }

    private Reservation capturedStatusWrite() {
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).updateStatusWithVersion(captor.capture(), anyInt(), any());
        return captor.getValue();
    }

    private Reservation capturedAssignmentWrite() {
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).updateAssignmentWithVersion(captor.capture(), anyInt(), any());
        return captor.getValue();
    }

    /**
     * 时段冲突用的另一张预约（同门店、同包厢、时段与之重叠）。
     * 默认包厢 3001、时段 19:00–21:00（与本预约 20:00–22:00 重叠）。
     */
    private static Reservation conflictingReservation(Long id, String reservationNo,
                                                      int startHour, int startMinute,
                                                      int endHour, int endMinute) {
        return conflictingReservation(id, reservationNo, startHour, startMinute, endHour, endMinute, 3001L);
    }

    private static Reservation conflictingReservation(Long id, String reservationNo,
                                                      int startHour, int startMinute,
                                                      int endHour, int endMinute, Long resourceId) {
        return new Reservation(id, TENANT_ID, STORE_ID, reservationNo, null, 99L, "KTV", resourceId, ROOM_TYPE_ID,
                LocalDateTime.of(2026, 9, 18, startHour, startMinute),
                LocalDateTime.of(2026, 9, 18, endHour, endMinute), 4, null, ReservationStatus.CONFIRMED,
                null, null, 0);
    }

    private AuditClient.AuditRecord capturedAudit() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }

    /** 失败留痕：本次调用只应产生一条 FAILURE 记录（不重复留痕、不写成功痕迹）。 */
    private AuditClient.AuditRecord capturedFailure() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, captor.getValue().result());
        return captor.getValue();
    }
}
