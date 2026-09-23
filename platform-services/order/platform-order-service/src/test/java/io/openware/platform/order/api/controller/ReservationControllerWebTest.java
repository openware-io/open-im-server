package io.openware.platform.order.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.platform.order.application.ReservationApplicationService;
import io.openware.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import io.openware.platform.order.infra.persistence.po.ReservationPo;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预约 Controller 层集成测试（standalone MockMvc + mock 依赖，验证 Web 层 + DTO 映射）。
 * 预约按房型创建：请求带 roomTypeId；带 resourceId 的旧 C 端请求显式 400（不给静默忽略）。
 */
class ReservationControllerWebTest {
    private MockMvc mvc;
    private final ReservationApplicationService reservationService = mock(ReservationApplicationService.class);
    private final CustomerLookupMapper customerLookupMapper = mock(CustomerLookupMapper.class);
    private ReservationController controller;

    @BeforeEach
    void setup() {
        controller = new ReservationController(reservationService, customerLookupMapper);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void listReturns200() throws Exception {
        TenantContextHolder.set(new TenantContext(7L, 8L, 1001L, 42L, 1, List.of("reservation.view")));
        when(reservationService.list(TimeRange.none())).thenReturn(List.of());
        mvc.perform(get("/business/reservations")).andExpect(status().isOk());
    }

    @Test
    void getReturns200() throws Exception {
        TenantContextHolder.set(new TenantContext(7L, 8L, 1001L, 42L, 1, List.of("reservation.view")));
        when(reservationService.get(1L)).thenReturn(new ReservationPo());
        mvc.perform(get("/business/reservations/1")).andExpect(status().isOk());
    }

    /** 创建预约：请求带 roomTypeId，门店取上下文（请求里的 storeId 不可信），命令里 resourceId 已不存在。 */
    @Test
    void createUsesSelectedStoreContextAndRoomType() throws Exception {
        TenantContextHolder.set(new TenantContext(7L, 8L, 1001L, 42L, 1, List.of("reservation.create")));
        when(customerLookupMapper.findMemberId(7L, 42L)).thenReturn(99L);
        when(reservationService.create(eq(7L), any(), any())).thenReturn(new ReservationPo());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/business/reservations")
                .contentType("application/json")
                .content("{\"businessType\":\"KTV\",\"roomTypeId\":55,\"startAt\":\"2026-09-04T19:30:00+08:00\",\"endAt\":\"2026-09-04T22:30:00+08:00\",\"partySize\":4,\"contact\":\"张三 13800138000\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ReservationApplicationService.CreateReservationCommand> captor =
                ArgumentCaptor.forClass(ReservationApplicationService.CreateReservationCommand.class);
        verify(reservationService).create(eq(7L), any(), captor.capture());
        assertEquals(1001L, captor.getValue().storeId());
        assertEquals(55L, captor.getValue().roomTypeId());
    }

    @Test
    void createIgnoresForgedStoreId() throws Exception {
        TenantContextHolder.set(new TenantContext(7L, 8L, 1001L, 42L, 1, List.of("reservation.create")));
        when(customerLookupMapper.findMemberId(7L, 42L)).thenReturn(99L);
        when(reservationService.create(eq(7L), any(), any())).thenReturn(new ReservationPo());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/business/reservations")
                .contentType("application/json")
                .content("{\"businessType\":\"KTV\",\"storeId\":9999,\"roomTypeId\":55,\"startAt\":\"2026-09-04T19:30:00+08:00\",\"endAt\":\"2026-09-04T22:30:00+08:00\",\"partySize\":4}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ReservationApplicationService.CreateReservationCommand> captor =
                ArgumentCaptor.forClass(ReservationApplicationService.CreateReservationCommand.class);
        verify(reservationService).create(eq(7L), any(), captor.capture());
        assertEquals(1001L, captor.getValue().storeId());
    }

    /**
     * 旧 C 端仍传 resourceId：显式 400 RESOURCE_ID_NOT_ALLOWED（不静默忽略，
     * 否则调用方会以为它选的包厢已被预约锁定）。
     */
    @Test
    void createRejectsResourceIdExplicitly() {
        TenantContextHolder.set(new TenantContext(7L, 8L, 1001L, 42L, 1, List.of("reservation.create")));
        when(customerLookupMapper.findMemberId(7L, 42L)).thenReturn(99L);

        ApiException ex = assertThrows(ApiException.class, () -> controller.create(null,
                new ReservationController.CreateReservationRequest("KTV", null, null, 1001L, 55L,
                        java.time.OffsetDateTime.parse("2026-09-04T19:30:00+08:00"),
                        java.time.OffsetDateTime.parse("2026-09-04T22:30:00+08:00"), 4, null)));

        assertEquals(400, ex.getStatus());
        assertEquals("RESOURCE_ID_NOT_ALLOWED", ex.getCode());
    }

    /** 到店分配包厢：转发 resourceId + override 到应用服务（权限复用 reservation.arrival）。 */
    @Test
    void assignRoomForwardsResourceAndOverride() throws Exception {
        TenantContextHolder.set(new TenantContext(7L, 8L, 1001L, 42L, 1, List.of("reservation.arrival")));
        when(reservationService.assignRoom(9L, 3001L, true)).thenReturn(new ReservationPo());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/business/reservations/9/assign-room")
                .contentType("application/json")
                .content("{\"resourceId\":3001,\"override\":true}"))
                .andExpect(status().isOk());

        verify(reservationService).assignRoom(9L, 3001L, true);
    }

    /** 分配包厢缺 resourceId：400 RESOURCE_ID_REQUIRED。 */
    @Test
    void assignRoomRequiresResourceId() {
        TenantContextHolder.set(new TenantContext(7L, 8L, 1001L, 42L, 1, List.of("reservation.arrival")));

        ApiException ex = assertThrows(ApiException.class,
                () -> controller.assignRoom(9L, new ReservationController.AssignRoomRequest(null, false)));

        assertEquals(400, ex.getStatus());
        assertEquals("RESOURCE_ID_REQUIRED", ex.getCode());
        assertTrue(ex.getMessage().contains("resourceId"));
    }

    /** 标记未到店：转发到应用服务（权限与「到店登记」同域 reservation.arrival）。 */
    @Test
    void noShowForwardsToService() throws Exception {
        TenantContextHolder.set(new TenantContext(7L, 8L, 1001L, 42L, 1, List.of("reservation.arrival")));
        when(reservationService.noShow(9L)).thenReturn(new ReservationPo());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/business/reservations/9/no-show"))
                .andExpect(status().isOk());

        verify(reservationService).noShow(9L);
    }

    /**
     * 取消预约要求权限 {@code reservation.cancel}（**不是** reservation.arrival：后者被授予了收银员，
     * 用它会让收银员也能取消预约），并把原因原样转发到应用服务。
     */
    @Test
    void cancelRequiresReservationCancelPermissionAndForwardsReason() throws Exception {
        TenantContextHolder.set(new TenantContext(7L, 8L, 1001L, 42L, 1, List.of("reservation.cancel")));
        when(reservationService.cancel(9L, "客人临时有事")).thenReturn(new ReservationPo());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/business/reservations/9/cancel")
                .contentType("application/json")
                .content("{\"reason\":\"客人临时有事\"}"))
                .andExpect(status().isOk());

        verify(reservationService).cancel(9L, "客人临时有事");
    }

    /** 只有 reservation.arrival（无 reservation.cancel）的账号不能取消预约：权限不足直接 403。 */
    @Test
    void cancelRejectsAccountWithoutReservationCancelPermission() {
        TenantContextHolder.set(new TenantContext(7L, 8L, 1001L, 42L, 1, List.of("reservation.arrival")));

        ApiException ex = assertThrows(ApiException.class, () -> controller.cancel(9L,
                new ReservationController.CancelReservationRequest("客人临时有事")));

        assertEquals(403, ex.getStatus());
        assertEquals("PERMISSION_DENIED", ex.getCode());
    }
}
