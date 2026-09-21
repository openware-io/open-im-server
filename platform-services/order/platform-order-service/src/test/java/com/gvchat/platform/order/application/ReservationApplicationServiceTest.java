package com.gvchat.platform.order.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.infrastructure.currency.CurrencyContextHolder;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import com.gvchat.platform.order.domain.reservation.model.ReservationStatus;
import com.gvchat.platform.order.infra.client.ResourceStateClient;
import com.gvchat.platform.order.infra.client.RoomTypeCatalogClient;
import com.gvchat.platform.order.infra.client.RoomTypeCatalogClient.RoomTypeView;
import com.gvchat.platform.order.infra.client.RoomTypeCatalogClient.RoomView;
import com.gvchat.platform.order.infra.client.TenantBusinessHoursClient;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ReservationMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvSessionPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import com.gvchat.platform.order.infra.persistence.po.ReservationPo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 预约改为「预约房型」后的核心规则（docs/renovation/KTV_RESERVATION_ROOM_TYPE.md §3/§5）：
 * 创建只认房型、房型校验与超订软保护、到店分配包厢（含幂等/覆盖/审计 before-after/fail-closed）、
 * 开台前置包厢、旧数据（只有 resource_id）兼容。
 */
class ReservationApplicationServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Long STORE_ID = 1001L;
    private static final Long ROOM_TYPE_ID = 55L;
    /** 新格式单号（mock 生成器返回值）：{@code <前缀><yyyyMMdd><当日序号>}。 */
    private static final String RESERVATION_NO = "R202609190001";
    private static final String ORDER_NO = "O202609190001";
    private static final OffsetDateTime START_AT = OffsetDateTime.parse("2026-09-18T20:00:00+08:00");
    private static final OffsetDateTime END_AT = OffsetDateTime.parse("2026-09-18T22:00:00+08:00");

    private ReservationMapper reservationMapper;
    private OrderMapper orderMapper;
    private KtvSessionApplicationService ktvSessionService;
    private ResourceStateClient resourceStateClient;
    private RoomTypeCatalogClient roomTypeCatalogClient;
    private TenantBusinessHoursClient businessHoursClient;
    private AuditClient auditClient;
    private DailySerialNumberGenerator dailySerialNumberGenerator;
    private ReservationApplicationService service;

    /**
     * 纯单元测试没有 MyBatis-Plus 的 Mapper 扫描，{@code LambdaUpdateWrapper.set(...)} 需要 lambda 列缓存，
     * 否则报「can not find lambda cache for this entity」。这里按资源域测试同款做法显式初始化表信息。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ReservationPo.class);
    }

    @BeforeEach
    void setUp() {
        reservationMapper = mock(ReservationMapper.class);
        orderMapper = mock(OrderMapper.class);
        ktvSessionService = mock(KtvSessionApplicationService.class);
        resourceStateClient = mock(ResourceStateClient.class);
        roomTypeCatalogClient = mock(RoomTypeCatalogClient.class);
        businessHoursClient = mock(TenantBusinessHoursClient.class);
        auditClient = mock(AuditClient.class);
        dailySerialNumberGenerator = mock(DailySerialNumberGenerator.class);
        service = new ReservationApplicationService(reservationMapper, orderMapper, ktvSessionService,
                resourceStateClient, roomTypeCatalogClient, businessHoursClient, auditClient,
                dailySerialNumberGenerator);
        // 单号生成器是外部依赖（真实实现走 ord_daily_serial 表）：这里按单据类型返回可断言的新格式号，
        // 生成规则本身由 DailySerialNumberGeneratorTest（H2 真跑）覆盖。
        when(dailySerialNumberGenerator.next(any(), any())).thenAnswer(invocation ->
                invocation.getArgument(0) == DailySerialNumberGenerator.DocType.ORDER
                        ? ORDER_NO
                        : RESERVATION_NO);
        // 默认「营业时间不可用」：不改变既有用例语义（跳过校验）。营业时间用例单独 stub。
        when(businessHoursClient.resolve(any())).thenReturn(java.util.Optional.empty());
        when(reservationMapper.insert(any(ReservationPo.class))).thenReturn(1);
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
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        givenBusinessHours("18:00", "05:00");

        ReservationPo created = service.create(TENANT_ID, "idem-bh-ok", command(ROOM_TYPE_ID));

        assertEquals("PENDING", created.getStatus());
    }

    /** 凌晨到店时间属于**前一个营业日**的时段（18:00–05:00 跨自然日），不能被当成越界。 */
    @Test
    void createAllowsEarlyMorningStartAtWhenHoursCrossMidnight() {
        givenRoomType();
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        givenBusinessHours("18:00", "05:00");
        OffsetDateTime startAt = OffsetDateTime.parse("2026-09-19T01:30:00+08:00");
        OffsetDateTime endAt = OffsetDateTime.parse("2026-09-19T04:30:00+08:00");

        ReservationPo created = service.create(TENANT_ID, "idem-bh-midnight",
                new ReservationApplicationService.CreateReservationCommand(
                        "KTV", 99L, STORE_ID, ROOM_TYPE_ID, startAt, endAt, 4, "张三 13800138000"));

        assertEquals("PENDING", created.getStatus());
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
        verify(reservationMapper, never()).insert(any(ReservationPo.class));
    }

    /** 租户服务读不到营业时间时**放行**（不阻断营业）：不抛异常、不误伤正常预约。 */
    @Test
    void createSkipsBusinessHoursCheckWhenConfigUnavailable() {
        givenRoomType();
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(businessHoursClient.resolve(any())).thenReturn(Optional.empty());
        OffsetDateTime startAt = OffsetDateTime.parse("2026-09-19T15:00:00+08:00");
        OffsetDateTime endAt = OffsetDateTime.parse("2026-09-19T18:00:00+08:00");

        ReservationPo created = service.create(TENANT_ID, "idem-bh-unavailable",
                new ReservationApplicationService.CreateReservationCommand(
                        "KTV", 99L, STORE_ID, ROOM_TYPE_ID, startAt, endAt, 4, "张三 13800138000"));

        assertEquals("PENDING", created.getStatus());
    }

    /** 创建预约只写房型：resource_id 留空（到店再分配），响应带房型名/编码。 */
    @Test
    void createWritesRoomTypeAndLeavesResourceUnassigned() {
        givenRoomType();
        when(reservationMapper.selectCount(any())).thenReturn(0L);

        ReservationPo created = service.create(TENANT_ID, "idem-1", command(ROOM_TYPE_ID));

        ArgumentCaptor<ReservationPo> captor = ArgumentCaptor.forClass(ReservationPo.class);
        verify(reservationMapper).insert(captor.capture());
        ReservationPo inserted = captor.getValue();
        assertEquals(ROOM_TYPE_ID, inserted.getRoomTypeId());
        assertNull(inserted.getResourceId(), "预约阶段不得写入具体包厢（到店才分配）");
        assertEquals("PENDING", inserted.getStatus());
        assertEquals("VIP", inserted.getRoomTypeCode());
        assertEquals("VIP 大包", inserted.getRoomTypeName());
        assertEquals(START_AT.withOffsetSameInstant(java.time.ZoneOffset.ofHours(8)).toLocalDateTime(),
                inserted.getStartAt());
        assertEquals(ROOM_TYPE_ID, created.getRoomTypeId());
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
        when(reservationMapper.selectCount(any())).thenReturn(2L);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(TENANT_ID, null, command(ROOM_TYPE_ID)));

        assertEquals(409, ex.getStatus());
        assertEquals("ROOM_TYPE_FULL", ex.getCode());
        verify(reservationMapper, never()).insert(any(ReservationPo.class));
    }

    /**
     * 统计口径回归：只有 PENDING / CONFIRMED / ARRIVED 占预约名额；
     * CONVERTED（已开台）绝不能再占——它已经兑现成会话，房间占用由资源域校验，
     * 重复计入会让「今天的小包明明空闲却提示已满」（DEBUG 现场 3 间小包被 3 条 CONVERTED 预约锁死）。
     */
    @Test
    void overbookingCountsOnlySlotHoldingStatuses() {
        givenRoomType();
        when(reservationMapper.selectCount(any())).thenReturn(0L);

        service.create(TENANT_ID, null, command(ROOM_TYPE_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ReservationPo>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(reservationMapper).selectCount(captor.capture());
        Wrapper<ReservationPo> wrapper = captor.getValue();
        java.util.Collection<Object> values =
                ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper)
                        .getParamNameValuePairs().values();
        assertTrue(wrapper.getSqlSegment().contains("status IN"), wrapper.getSqlSegment());
        assertTrue(values.contains(ReservationStatus.PENDING.name()), "待确认应占名额");
        assertTrue(values.contains(ReservationStatus.CONFIRMED.name()), "已确认应占名额");
        assertTrue(values.contains(ReservationStatus.ARRIVED.name()), "已到店应占名额");
        assertFalse(values.contains(ReservationStatus.CONVERTED.name()), "已开台不应再占名额（房间占用由资源域校验）");
        assertFalse(values.contains(ReservationStatus.CANCELLED.name()), "已取消不应占名额");
        assertFalse(values.contains(ReservationStatus.NO_SHOW.name()), "未到店不应占名额");
    }

    /** 预约数未到启用包厢数：放行。 */
    @Test
    void createAllowsWhenBelowCapacity() {
        givenRoomType();
        when(reservationMapper.selectCount(any())).thenReturn(1L);

        ReservationPo created = service.create(TENANT_ID, null, command(ROOM_TYPE_ID));

        assertEquals(ROOM_TYPE_ID, created.getRoomTypeId());
        verify(reservationMapper).insert(any(ReservationPo.class));
    }

    /** 新单号规则：预约号由统一生成器分配（{@code R<yyyyMMdd><当日序号>}），不再用 R+UUID。 */
    @Test
    void createUsesDailySerialReservationNumber() {
        givenRoomType();
        when(reservationMapper.selectCount(any())).thenReturn(0L);

        ReservationPo created = service.create(TENANT_ID, "idem-no-1", command(ROOM_TYPE_ID));

        assertEquals(RESERVATION_NO, created.getReservationNo());
        ArgumentCaptor<ReservationPo> captor = ArgumentCaptor.forClass(ReservationPo.class);
        verify(reservationMapper).insert(captor.capture());
        assertEquals(RESERVATION_NO, captor.getValue().getReservationNo(), "落库的必须是生成器给的号");
        verify(dailySerialNumberGenerator).next(DailySerialNumberGenerator.DocType.RESERVATION, TENANT_ID);
    }

    /**
     * 幂等重试不换号：同 Idempotency-Key 命中已有预约时**直接返回原号且不再发号**
     * （生成器零交互 ⇒ 重试不会消耗/换掉当日序号）。
     */
    @Test
    void createReturnsExistingNumberOnIdempotentRetry() {
        ReservationPo existing = reservation(ReservationStatusFixture.PENDING, null, ROOM_TYPE_ID);
        existing.setReservationNo(RESERVATION_NO);
        when(reservationMapper.selectOne(any())).thenReturn(existing);

        ReservationPo retried = service.create(TENANT_ID, "idem-no-retry", command(ROOM_TYPE_ID));

        assertEquals(RESERVATION_NO, retried.getReservationNo());
        verifyNoInteractions(dailySerialNumberGenerator);
        verify(reservationMapper, never()).insert(any(ReservationPo.class));
    }

    /** 序号服务不可用：失败关闭（503 DOC_NO_SEQUENCE_UNAVAILABLE），不降级、不落库一张号不合法/可能重的预约。 */
    @Test
    void createFailsClosedWhenSequenceUnavailable() {
        givenRoomType();
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(dailySerialNumberGenerator.next(any(), any()))
                .thenThrow(new BusinessException(DailySerialNumberGenerator.CODE_SEQUENCE_UNAVAILABLE,
                        "单号生成失败：序号服务不可用，请稍后重试"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(TENANT_ID, "idem-no-fail", command(ROOM_TYPE_ID)));

        assertEquals(DailySerialNumberGenerator.CODE_SEQUENCE_UNAVAILABLE, ex.getCode());
        verify(reservationMapper, never()).insert(any(ReservationPo.class));
    }

    /** 资源域读不到房型：失败关闭（503 RESOURCE_STATE_UNAVAILABLE），绝不静默放行未校验的房型。 */
    @Test
    void createFailsClosedWhenCatalogUnavailable() {
        when(roomTypeCatalogClient.roomType(STORE_ID, ROOM_TYPE_ID))
                .thenThrow(new BusinessException("RESOURCE_STATE_UNAVAILABLE", "房型服务暂时不可用"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(TENANT_ID, null, command(ROOM_TYPE_ID)));

        assertEquals("RESOURCE_STATE_UNAVAILABLE", ex.getCode());
        verify(reservationMapper, never()).insert(any(ReservationPo.class));
    }

    // ------------------------------------------------------------------ 到店分配包厢

    /**
     * 时段冲突（回归）：预约**不写资源占用**，因此房态永远看不出「这个时段已被别的预约锁了」。
     * 同一包厢、重叠时段的另一张未取消预约必须让分配失败（409 ROOM_RESERVED_OVERLAP）。
     */
    @Test
    void assignRoomRejectsRoomAlreadyReservedInSameWindow() {
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, ROOM_TYPE_ID, STORE_ID, "VIP 01"));
        when(resourceStateClient.requireState(3001L)).thenReturn(new ResourceStateClient.RoomState(
                true, "IDLE", null, "VIP 01"));
        ReservationPo conflict = conflictingReservation(77L, "R2026091800000000099", 19, 0, 21, 0);
        when(reservationMapper.selectList(any())).thenReturn(List.of(conflict));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals(409, ex.getStatus());
        assertEquals("ROOM_RESERVED_OVERLAP", ex.getCode());
        assertTrue(ex.getMessage().contains("R2026091800000000099"), ex.getMessage());
        assertTrue(ex.getMessage().contains("19:00"), ex.getMessage());
        verify(reservationMapper, never()).update(isNull(), any());
    }

    /**
     * 分配候选由服务端算：可分配性、原因（房态 / 本时段预约冲突）、当前已分配标记一次给全。
     * 这是「分配包厢没有做占用状态过滤」的修复口径 —— 前端不再自己合并资源列表。
     */
    @Test
    void assignableRoomsCarriesReasonsConflictsAndCurrentAssignment() {
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, 3001L, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
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
        when(reservationMapper.selectList(any())).thenReturn(List.of(
                conflictingReservation(88L, "R2026091800000000088", 20, 30, 23, 0, 3004L)));

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
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, ROOM_TYPE_ID, STORE_ID, "VIP 01"));
        when(resourceStateClient.requireState(3001L)).thenReturn(new ResourceStateClient.RoomState(
                true, "IDLE", null, "VIP 01"));
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        ReservationPo assigned = service.assignRoom(9L, 3001L, false);

        assertEquals(3001L, assigned.getResourceId());
        assertEquals("CONFIRMED", assigned.getStatus(), "提前分配包厢不得把预约改成「客户已到店」");
        assertNull(assigned.getArrivedAt(), "分配包厢不写真实到店时间");
        assertEquals("VIP 01", assigned.getResourceName());
        assertUpdateSetsResourceIdWithoutStatus();
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
        ReservationPo po = reservation(ReservationStatusFixture.PENDING, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, ROOM_TYPE_ID, STORE_ID, "VIP 01"));
        when(resourceStateClient.requireState(3001L)).thenReturn(new ResourceStateClient.RoomState(
                true, "IDLE", null, "VIP 01"));
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        ReservationPo assigned = service.assignRoom(9L, 3001L, false);

        assertEquals(3001L, assigned.getResourceId());
        assertEquals("PENDING", assigned.getStatus());
    }

    /** 已分配同一包厢：幂等成功，不写库、不重复留痕。 */
    @Test
    void assignRoomIsIdempotentForSameRoom() {
        ReservationPo po = reservation(ReservationStatusFixture.ARRIVED, 3001L, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);

        ReservationPo assigned = service.assignRoom(9L, 3001L, false);

        assertEquals(3001L, assigned.getResourceId());
        verify(reservationMapper, never()).update(any(), any());
        verify(resourceStateClient, never()).requireRoom(anyLong());
        verifyNoInteractions(auditClient);
    }

    /** 换包厢未显式覆盖：409 RESERVATION_ROOM_ASSIGNED（避免误改派）。 */
    @Test
    void assignRoomRejectsChangeWithoutOverride() {
        ReservationPo po = reservation(ReservationStatusFixture.ARRIVED, 3001L, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3002L, false));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_ROOM_ASSIGNED", ex.getCode());
        verify(reservationMapper, never()).update(any(), any());
    }

    /** 换包厢显式覆盖：成功且审计 before/after 都带上。 */
    @Test
    void assignRoomOverridesWithTrail() {
        ReservationPo po = reservation(ReservationStatusFixture.ARRIVED, 3001L, ROOM_TYPE_ID);
        po.setResourceName("VIP 01");
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(resourceStateClient.requireRoom(3002L)).thenReturn(roomSnapshot(3002L, ROOM_TYPE_ID, STORE_ID, "VIP 02"));
        when(resourceStateClient.requireState(3002L)).thenReturn(new ResourceStateClient.RoomState(
                true, "IDLE", null, "VIP 02"));
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        ReservationPo assigned = service.assignRoom(9L, 3002L, true);

        assertEquals(3002L, assigned.getResourceId());
        AuditClient.AuditRecord record = capturedAudit();
        assertTrue(record.detailJson().contains("\"before\":{\"resourceId\":3001,\"resourceName\":\"VIP 01\"}"),
                record.detailJson());
        assertTrue(record.detailJson().contains("\"after\":{\"resourceId\":3002,\"resourceName\":\"VIP 02\"}"),
                record.detailJson());
        assertTrue(record.detailJson().contains("\"override\":true"), record.detailJson());
    }

    /** 包厢房型与预约房型不一致：400 ROOM_TYPE_MISMATCH。 */
    @Test
    void assignRoomRejectsRoomOfAnotherRoomType() {
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, 77L, STORE_ID, "小包 01"));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals(400, ex.getStatus());
        assertEquals("ROOM_TYPE_MISMATCH", ex.getCode());
    }

    /** 包厢不属于预约门店：400 ROOM_STORE_MISMATCH。 */
    @Test
    void assignRoomRejectsRoomOfAnotherStore() {
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, ROOM_TYPE_ID, 2002L, "VIP 01"));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals(400, ex.getStatus());
        assertEquals("ROOM_STORE_MISMATCH", ex.getCode());
    }

    /** 包厢使用中/清洁中：409 ROOM_UNAVAILABLE（当前不可分配）。 */
    @Test
    void assignRoomRejectsUnavailableRoom() {
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(resourceStateClient.requireRoom(3001L)).thenReturn(roomSnapshot(3001L, ROOM_TYPE_ID, STORE_ID, "VIP 01"));
        when(resourceStateClient.requireState(3001L)).thenReturn(new ResourceStateClient.RoomState(
                false, "OCCUPIED", "使用中", "VIP 01"));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals(409, ex.getStatus());
        assertEquals("ROOM_UNAVAILABLE", ex.getCode());
        verify(reservationMapper, never()).update(any(), any());
    }

    /** 房态服务不可达：fail-closed（503），不允许「拿不到房态也把包厢分出去」。 */
    @Test
    void assignRoomFailsClosedWhenResourceUnavailable() {
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(resourceStateClient.requireRoom(3001L))
                .thenThrow(new BusinessException("RESOURCE_STATE_UNAVAILABLE", "房态服务暂时不可用"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals("RESOURCE_STATE_UNAVAILABLE", ex.getCode());
        verify(reservationMapper, never()).update(any(), any());
    }

    /** 历史预约没有房型：409 RESERVATION_ROOM_TYPE_REQUIRED（不给旧数据隐式改派能力）。 */
    @Test
    void assignRoomRejectsLegacyReservationWithoutRoomType() {
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, 3001L, null);
        when(reservationMapper.selectById(9L)).thenReturn(po);

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3002L, true));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_ROOM_TYPE_REQUIRED", ex.getCode());
        verify(resourceStateClient, never()).requireRoom(anyLong());
    }

    /** 已取消/已开台：409 RESERVATION_STATUS_INVALID。 */
    @Test
    void assignRoomRejectsCancelledReservation() {
        ReservationPo po = reservation(ReservationStatusFixture.CANCELLED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3001L, false));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
    }

    /** 缺少 resourceId：400 RESOURCE_ID_REQUIRED。 */
    @Test
    void assignRoomRequiresResourceId() {
        when(reservationMapper.selectById(9L)).thenReturn(reservation(ReservationStatusFixture.CONFIRMED, null,
                ROOM_TYPE_ID));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, null, false));

        assertEquals(400, ex.getStatus());
        assertEquals("RESOURCE_ID_REQUIRED", ex.getCode());
    }

    // ------------------------------------------------------------------ 开台前置

    /** 未分配包厢就开台：409 RESERVATION_ROOM_NOT_ASSIGNED。 */
    @Test
    void openTableRequiresAssignedRoom() {
        ReservationPo po = reservation(ReservationStatusFixture.ARRIVED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);

        ApiException ex = assertThrows(ApiException.class, () -> service.openTable(9L, null));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_ROOM_NOT_ASSIGNED", ex.getCode());
        verify(orderMapper, never()).insert(any(OrderPo.class));
    }

    /** 旧数据兼容：只有 resource_id 的历史预约仍可开台，用既有包厢建会话，币种取当前上下文（不再硬编码 CNY）。 */
    @Test
    void openTableStillWorksForLegacyReservationWithOnlyResourceId() {
        ReservationPo po = reservation(ReservationStatusFixture.ARRIVED, 3001L, null);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(orderMapper.insert(any(OrderPo.class))).thenAnswer(invocation -> {
            OrderPo order = invocation.getArgument(0);
            order.setId(88L);
            return 1;
        });
        KtvSessionPo session = new KtvSessionPo();
        session.setId(555L);
        when(ktvSessionService.create(eq(TENANT_ID), eq(88L), eq(3001L))).thenReturn(session);
        OrderPo persisted = new OrderPo();
        persisted.setId(88L);
        persisted.setStatus("SERVING");
        when(orderMapper.selectById(88L)).thenReturn(persisted);
        when(reservationMapper.update(isNull(), any())).thenReturn(1);
        CurrencyContextHolder.set(Currency.CNY);

        OrderPo opened = service.openTable(9L, null);

        assertEquals(555L, opened.getSessionId());
        verify(ktvSessionService).create(TENANT_ID, 88L, 3001L);
        ArgumentCaptor<OrderPo> orderCaptor = ArgumentCaptor.forClass(OrderPo.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertEquals("CNY", orderCaptor.getValue().getCurrencyCode(),
                "开台订单币种取 CurrencyResolver（当前上下文 CNY），不是硬编码常量");
    }

    /** 没有币种上下文时回退 USD（老 token 兼容），绝不因缺 claim 失败。 */
    @Test
    void openTableFallsBackToDefaultCurrencyWithoutContext() {
        ReservationPo po = reservation(ReservationStatusFixture.ARRIVED, 3001L, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(orderMapper.insert(any(OrderPo.class))).thenAnswer(invocation -> {
            OrderPo order = invocation.getArgument(0);
            order.setId(88L);
            return 1;
        });
        KtvSessionPo session = new KtvSessionPo();
        session.setId(555L);
        when(ktvSessionService.create(any(), any(), any())).thenReturn(session);
        when(orderMapper.selectById(88L)).thenReturn(new OrderPo());
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        service.openTable(9L, null);

        ArgumentCaptor<OrderPo> orderCaptor = ArgumentCaptor.forClass(OrderPo.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertEquals("USD", orderCaptor.getValue().getCurrencyCode());
    }

    // ------------------------------------------------------------------ 取消预约（运营代客取消）

    /** 原因必填：空白 → 400 CANCEL_REASON_REQUIRED，且不改库、不留痕。 */
    @Test
    void cancelRequiresReason() {
        ReservationPo po = reservation(ReservationStatusFixture.PENDING, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);

        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(9L, "   "));

        assertEquals(400, ex.getStatus());
        assertEquals("CANCEL_REASON_REQUIRED", ex.getCode());
        assertEquals("取消预约必须填写原因", ex.getMessage());
        verify(reservationMapper, never()).update(isNull(), any());
        verifyNoInteractions(auditClient);
    }

    /** 原因超长：400 CANCEL_REASON_TOO_LONG（与其它自由文本同为 255 上限）。 */
    @Test
    void cancelRejectsTooLongReason() {
        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(9L, "原".repeat(256)));

        assertEquals(400, ex.getStatus());
        assertEquals("CANCEL_REASON_TOO_LONG", ex.getCode());
        verifyNoInteractions(reservationMapper);
    }

    /** PENDING 预约可取消：状态 → CANCELLED，审计 reservation.cancel 带前后状态/原因/预约号/房型。 */
    @Test
    void cancelPendingReservationWritesAuditWithBeforeAfterAndReason() {
        ReservationPo po = reservation(ReservationStatusFixture.PENDING, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        ReservationPo cancelled = service.cancel(9L, " 客人临时有事 ");

        assertEquals("CANCELLED", cancelled.getStatus());
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("reservation.cancel", record.action());
        assertEquals("预约取消", record.actionLabel());
        assertEquals("reservation", record.resourceType());
        assertEquals("9", record.resourceId());
        assertEquals("R2026091800000000001", record.resourceName());
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
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        ReservationPo cancelled = service.cancel(9L, "客人要求取消");

        assertEquals("CANCELLED", cancelled.getStatus());
        AuditClient.AuditRecord record = capturedAudit();
        assertTrue(record.detailJson().contains("\"beforeStatus\":\"CONFIRMED\""), record.detailJson());
    }

    /** 已到店（ARRIVED）：409 RESERVATION_STATUS_INVALID，提示改走「取消订单」，失败留痕 FAILURE。 */
    @Test
    void cancelRejectsArrivedReservation() {
        ReservationPo po = reservation(ReservationStatusFixture.ARRIVED, 3001L, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);

        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(9L, "客人要求取消"));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
        assertTrue(ex.getMessage().contains("取消订单"), ex.getMessage());
        verify(reservationMapper, never()).update(isNull(), any());
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
        ReservationPo po = reservation(ReservationStatusFixture.CONVERTED, 3001L, ROOM_TYPE_ID);
        po.setOrderId(88L);
        when(reservationMapper.selectById(9L)).thenReturn(po);

        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(9L, "客人要求取消"));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
        assertTrue(ex.getMessage().contains("取消订单"), ex.getMessage());
    }

    /** 已取消：409（不重复取消），且失败留痕 FAILURE（不覆盖成功路径的幂等键）。 */
    @Test
    void cancelRejectsAlreadyCancelledReservation() {
        ReservationPo po = reservation(ReservationStatusFixture.CANCELLED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);

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
        ReservationPo legacy = reservation(ReservationStatusFixture.ARRIVED, 3001L, null);
        ReservationPo byRoomType = reservation(ReservationStatusFixture.PENDING, null, ROOM_TYPE_ID);
        when(reservationMapper.selectList(any())).thenReturn(List.of(legacy, byRoomType));
        when(roomTypeCatalogClient.roomTypes(STORE_ID)).thenReturn(List.of(activeRoomType()));
        when(roomTypeCatalogClient.rooms(STORE_ID)).thenReturn(List.of(
                new RoomView(3001L, "VIP 01", "V001", ROOM_TYPE_ID, "ENABLED", 12)));

        List<ReservationPo> rows = service.list(TimeRange.none());

        assertEquals("VIP 01", rows.get(0).getResourceName(), "历史预约回退展示旧包厢");
        assertNull(rows.get(0).getRoomTypeName());
        assertEquals("VIP 大包", rows.get(1).getRoomTypeName());
        assertEquals("VIP", rows.get(1).getRoomTypeCode());
        assertNull(rows.get(1).getResourceName(), "未分配包厢时没有包厢名，前端展示「到店后分配」");
    }

    /** 资源域读不到时只丢展示字段，列表本身照常返回（读路径降级）。 */
    @Test
    void listDegradesWhenCatalogUnavailable() {
        ReservationPo po = reservation(ReservationStatusFixture.PENDING, null, ROOM_TYPE_ID);
        when(reservationMapper.selectList(any())).thenReturn(List.of(po));
        when(roomTypeCatalogClient.roomTypes(STORE_ID)).thenThrow(new BusinessException("RESOURCE_STATE_UNAVAILABLE", "x"));

        List<ReservationPo> rows = service.list(TimeRange.none());

        assertEquals(1, rows.size());
        assertNull(rows.get(0).getRoomTypeName());
    }

    // ------------------------------------------------------------------ 确认/到店/分配/开台留痕

    /** 确认预约：状态 → CONFIRMED 且写 SUCCEEDED 留痕（此前确认成功完全没有审计）。 */
    @Test
    void confirmWritesSucceededAudit() {
        ReservationPo po = reservation(ReservationStatusFixture.PENDING, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        ReservationPo confirmed = service.confirm(9L, 0);

        assertEquals("CONFIRMED", confirmed.getStatus());
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("reservation.confirm", record.action());
        assertEquals("预约确认", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertNull(record.errorCode());
    }

    /** 状态不符（已到店）时确认失败：FAILURE + 稳定业务码，且不带幂等键。 */
    @Test
    void confirmWritesFailureAuditWithStableErrorCode() {
        ReservationPo po = reservation(ReservationStatusFixture.ARRIVED, 3001L, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);

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
        ReservationPo po = reservation(ReservationStatusFixture.PENDING, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.confirm(9L, 7));

        assertEquals("RESERVATION_VERSION_CONFLICT", ex.getCode());
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.confirm", record.action());
        assertEquals("RESERVATION_VERSION_CONFLICT", record.errorCode());
    }

    /** 到店：CONFIRMED → ARRIVED 且写 SUCCEEDED 留痕。 */
    @Test
    void arrivalWritesSucceededAudit() {
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        ReservationPo arrived = service.arrival(9L, "客人已到");

        assertEquals("ARRIVED", arrived.getStatus());
        assertNotNull(arrived.getArrivedAt(), "到店登记必须写真实到店时间（arrived_at）");
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("reservation.arrival", record.action());
        assertEquals("预约到店", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    }

    /** 未确认（PENDING）不能直接到店：FAILURE + RESERVATION_STATUS_INVALID。 */
    @Test
    void arrivalWritesFailureAuditWithStableErrorCode() {
        when(reservationMapper.selectById(9L))
                .thenReturn(reservation(ReservationStatusFixture.PENDING, null, ROOM_TYPE_ID));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.arrival(9L, "客人已到"));

        assertEquals("RESERVATION_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.arrival", record.action());
        assertEquals("RESERVATION_STATUS_INVALID", record.errorCode());
    }

    /** 分配包厢失败（包厢使用中）：FAILURE + ROOM_UNAVAILABLE。 */
    @Test
    void assignRoomWritesFailureAuditWithStableErrorCode() {
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, null, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
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
        when(reservationMapper.selectById(9L))
                .thenReturn(reservation(ReservationStatusFixture.ARRIVED, 3001L, ROOM_TYPE_ID));

        ApiException ex = assertThrows(ApiException.class, () -> service.assignRoom(9L, 3002L, false));

        assertEquals("RESERVATION_ROOM_ASSIGNED", ex.getCode());
        verify(reservationMapper, never()).update(isNull(), any());
        AuditClient.AuditRecord record = capturedFailure();
        assertEquals("reservation.assign_room", record.action());
        assertEquals("RESERVATION_ROOM_ASSIGNED", record.errorCode());
    }

    /** 预约开台：生成订单 + 会话并写 SUCCEEDED 留痕（此前开台完全没有审计）。 */
    @Test
    void openTableWritesSucceededAudit() {
        ReservationPo po = reservation(ReservationStatusFixture.ARRIVED, 3001L, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(orderMapper.insert(any(OrderPo.class))).thenAnswer(invocation -> {
            OrderPo order = invocation.getArgument(0);
            order.setId(88L);
            return 1;
        });
        KtvSessionPo session = new KtvSessionPo();
        session.setId(555L);
        when(ktvSessionService.create(any(), any(), any())).thenReturn(session);
        when(orderMapper.selectById(88L)).thenReturn(new OrderPo());
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        OrderPo order = service.openTable(9L, null);

        assertEquals(555L, order.getSessionId());
        assertEquals("CONVERTED", po.getStatus());
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("reservation.open_table", record.action());
        assertEquals("预约开台", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertTrue(record.detailJson().contains("\"orderId\":88"), record.detailJson());
    }

    /** 预约开台产生的订单同样走统一生成器：{@code O<yyyyMMdd><当日序号>}（不能漏掉这条建单入口）。 */
    @Test
    void openTableUsesDailySerialOrderNumber() {
        ReservationPo po = reservation(ReservationStatusFixture.ARRIVED, 3001L, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(orderMapper.insert(any(OrderPo.class))).thenAnswer(invocation -> {
            OrderPo order = invocation.getArgument(0);
            order.setId(88L);
            return 1;
        });
        KtvSessionPo session = new KtvSessionPo();
        session.setId(555L);
        when(ktvSessionService.create(any(), any(), any())).thenReturn(session);
        when(orderMapper.selectById(88L)).thenReturn(new OrderPo());
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        service.openTable(9L, null);

        ArgumentCaptor<OrderPo> captor = ArgumentCaptor.forClass(OrderPo.class);
        verify(orderMapper).insert(captor.capture());
        assertEquals(ORDER_NO, captor.getValue().getOrderNo(), "开台订单号必须来自统一生成器");
        verify(dailySerialNumberGenerator).next(DailySerialNumberGenerator.DocType.ORDER, TENANT_ID);
    }

    /** 未确认（PENDING）的预约不能开台：FAILURE + RESERVATION_STATUS_INVALID（先确认再开台）。 */
    @Test
    void openTableWritesFailureAuditWhenNotConfirmed() {
        when(reservationMapper.selectById(9L))
                .thenReturn(reservation(ReservationStatusFixture.PENDING, 3001L, ROOM_TYPE_ID));

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
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, 3001L, ROOM_TYPE_ID);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(orderMapper.insert(any(OrderPo.class))).thenAnswer(invocation -> {
            OrderPo order = invocation.getArgument(0);
            order.setId(88L);
            return 1;
        });
        KtvSessionPo session = new KtvSessionPo();
        session.setId(555L);
        when(ktvSessionService.create(any(), any(), any())).thenReturn(session);
        when(orderMapper.selectById(88L)).thenReturn(new OrderPo());
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        service.openTable(9L, null);

        assertEquals("CONVERTED", po.getStatus());
        assertNotNull(po.getArrivedAt(), "CONFIRMED 直接开台必须隐含登记到店时间");
        AuditClient.AuditRecord record = capturedAudit();
        assertTrue(record.detailJson().contains("\"arrivalImplied\":true"), record.detailJson());
    }

    /** 重复开台：已回填 order_id（状态已是 CONVERTED）时返回既有订单，不重复建单/建会话。 */
    @Test
    void openTableIsIdempotentForConvertedReservation() {
        ReservationPo po = reservation(ReservationStatusFixture.CONVERTED, 3001L, ROOM_TYPE_ID);
        po.setOrderId(88L);
        when(reservationMapper.selectById(9L)).thenReturn(po);
        OrderPo existing = new OrderPo();
        existing.setId(88L);
        existing.setStatus("SERVING");
        when(orderMapper.selectById(88L)).thenReturn(existing);
        KtvSessionPo session = new KtvSessionPo();
        session.setId(555L);
        when(ktvSessionService.findByOrderId(88L)).thenReturn(session);

        OrderPo opened = service.openTable(9L, null);

        assertEquals(88L, opened.getId());
        assertEquals(555L, opened.getSessionId());
        verify(orderMapper, never()).insert(any(OrderPo.class));
        verifyNoInteractions(auditClient);
    }

    /** 已到店但未分配包厢：FAILURE + RESERVATION_ROOM_NOT_ASSIGNED。 */
    @Test
    void openTableWritesFailureAuditWhenRoomNotAssigned() {
        when(reservationMapper.selectById(9L))
                .thenReturn(reservation(ReservationStatusFixture.ARRIVED, null, ROOM_TYPE_ID));

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
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, 3001L, ROOM_TYPE_ID);
        po.setStartAt(LocalDateTime.now().minusHours(2));
        when(reservationMapper.selectById(9L)).thenReturn(po);
        when(reservationMapper.update(isNull(), any())).thenReturn(1);

        ReservationPo noShow = service.noShow(9L);

        assertEquals("NO_SHOW", noShow.getStatus());
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("reservation.no_show", record.action());
        assertEquals("预约未到店", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertTrue(record.detailJson().contains("\"beforeStatus\":\"CONFIRMED\""), record.detailJson());
    }

    /** 预约开始时间还没到：409 RESERVATION_NOT_STARTED（没到点谈不上未到店），且不写库。 */
    @Test
    void noShowRejectsBeforeStartTime() {
        ReservationPo po = reservation(ReservationStatusFixture.CONFIRMED, 3001L, ROOM_TYPE_ID);
        po.setStartAt(LocalDateTime.now().plusHours(3));
        when(reservationMapper.selectById(9L)).thenReturn(po);

        ApiException ex = assertThrows(ApiException.class, () -> service.noShow(9L));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_NOT_STARTED", ex.getCode());
        verify(reservationMapper, never()).update(isNull(), any());
    }

    /** 已到店/已开台不能标未到店：409 RESERVATION_STATUS_INVALID（走取消订单）。 */
    @Test
    void noShowRejectsArrivedReservation() {
        ReservationPo po = reservation(ReservationStatusFixture.ARRIVED, 3001L, ROOM_TYPE_ID);
        po.setStartAt(LocalDateTime.now().minusHours(2));
        when(reservationMapper.selectById(9L)).thenReturn(po);

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

    private static ReservationPo reservation(ReservationStatusFixture fixture, Long resourceId, Long roomTypeId) {
        ReservationPo po = new ReservationPo();
        po.setId(9L);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setReservationNo("R2026091800000000001");
        po.setBusinessType("KTV");
        po.setCustomerId(99L);
        po.setResourceId(resourceId);
        po.setRoomTypeId(roomTypeId);
        po.setPartySize(4);
        po.setStartAt(LocalDateTime.of(2026, 9, 18, 20, 0));
        po.setEndAt(LocalDateTime.of(2026, 9, 18, 22, 0));
        po.setStatus(fixture.name());
        po.setVersion(0);
        return po;
    }

    @SuppressWarnings("unchecked")
    private void assertUpdateSetsResourceIdWithoutStatus() {
        ArgumentCaptor<LambdaUpdateWrapper<ReservationPo>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(reservationMapper).update(isNull(), captor.capture());
        Wrapper<ReservationPo> wrapper = captor.getValue();
        String sqlSet = ((LambdaUpdateWrapper<ReservationPo>) wrapper).getSqlSet();
        assertTrue(sqlSet.contains("resource_id"), sqlSet);
        assertFalse(sqlSet.contains("status"), "分配包厢不得顺手改状态：" + sqlSet);
    }

    /**
     * 时段冲突用的另一张预约（同门店、同包厢、时段与之重叠）。
     * 默认包厢 3001、时段 19:00–21:00（与本预约 20:00–22:00 重叠）。
     */
    private static ReservationPo conflictingReservation(Long id, String reservationNo,
                                                        int startHour, int startMinute,
                                                        int endHour, int endMinute) {
        return conflictingReservation(id, reservationNo, startHour, startMinute, endHour, endMinute, 3001L);
    }

    private static ReservationPo conflictingReservation(Long id, String reservationNo,
                                                        int startHour, int startMinute,
                                                        int endHour, int endMinute, Long resourceId) {
        ReservationPo po = new ReservationPo();
        po.setId(id);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setReservationNo(reservationNo);
        po.setResourceId(resourceId);
        po.setRoomTypeId(ROOM_TYPE_ID);
        po.setStartAt(LocalDateTime.of(2026, 9, 18, startHour, startMinute));
        po.setEndAt(LocalDateTime.of(2026, 9, 18, endHour, endMinute));
        po.setStatus("CONFIRMED");
        return po;
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

    /** 只用于构造状态字符串，避免测试里散落魔法字符串。 */
    private enum ReservationStatusFixture {
        PENDING,
        CONFIRMED,
        ARRIVED,
        CONVERTED,
        CANCELLED
    }
}
