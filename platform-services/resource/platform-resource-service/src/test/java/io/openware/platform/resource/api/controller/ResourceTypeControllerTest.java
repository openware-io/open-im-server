package io.openware.platform.resource.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.resource.application.RoomTypeApplicationService;
import io.openware.platform.resource.handler.GlobalExceptionHandler;
import io.openware.platform.resource.infra.persistence.mapper.ResourceMapper;
import io.openware.platform.resource.infra.persistence.mapper.RoomTypeMapper;
import io.openware.platform.resource.infra.persistence.po.RoomTypePo;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * GET /api/v1/admin/resources/types 的 HTTP 契约回归：不带 status 必须 200 返回全部（线上曾因
 * {@code status.trim()} 抛 NPE 变 500）、带合法 status 过滤、带非法 status 给可读的 400 + code。
 *
 * <p>用真实 {@link RoomTypeApplicationService} + mock mapper（资源服务无 H2 schema），
 * 经 MockMvc + {@link GlobalExceptionHandler} 断言真实 HTTP 状态，而不是只看返回值。
 */
class ResourceTypeControllerTest {
  private MockMvc mockMvc;
  private RoomTypeMapper roomTypeMapper;

  @BeforeEach
  void setUp() {
    roomTypeMapper = mock(RoomTypeMapper.class);
    RoomTypeApplicationService service =
        new RoomTypeApplicationService(roomTypeMapper, mock(ResourceMapper.class));
    mockMvc = MockMvcBuilders.standaloneSetup(new ResourceTypeController(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
    TenantContextHolder.set(new TenantContext(1L, null, 100L, 1L, 1, List.of("resource.manage")));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /** 前端「房型管理」列表的真实调用：不带 status → 200 + 全部房型（不再 500 NPE）。 */
  @Test
  void listWithoutStatusReturnsAllTypesWith200() throws Exception {
    when(roomTypeMapper.selectList(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包"),
        roomType(32L, "SMALL", "小包")));

    mockMvc.perform(get("/admin/resources/types"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].code").value("VIP"))
        .andExpect(jsonPath("$[1].code").value("SMALL"));
  }

  /** status= 空串按「不过滤」处理，同样 200 返回全部。 */
  @Test
  void listWithBlankStatusReturnsAllTypesWith200() throws Exception {
    when(roomTypeMapper.selectList(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    mockMvc.perform(get("/admin/resources/types").param("status", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void listWithActiveStatusReturns200() throws Exception {
    when(roomTypeMapper.selectList(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    mockMvc.perform(get("/admin/resources/types").param("status", "ACTIVE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("ACTIVE"));
  }

  @Test
  void listWithDisabledStatusReturns200() throws Exception {
    when(roomTypeMapper.selectList(any())).thenReturn(List.of());

    mockMvc.perform(get("/admin/resources/types").param("status", "DISABLED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /** 非法状态值：400 + 可读中文，不能是 500，也不能静默返回空列表。 */
  @Test
  void listWithInvalidStatusReturns400WithCode() throws Exception {
    mockMvc.perform(get("/admin/resources/types").param("status", "DELETED"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("ROOM_TYPE_STATUS_INVALID"))
        .andExpect(jsonPath("$.message").value("房型状态筛选值只能是 ACTIVE 或 DISABLED"));
  }

  /** 跨门店 storeId 仍是 403（可选参数判空与业务校验都不受影响）。 */
  @Test
  void listWithAnotherStoreReturns403() throws Exception {
    mockMvc.perform(get("/admin/resources/types").param("storeId", "999"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("STORE_SCOPE_DENIED"));
  }

  private static RoomTypePo roomType(Long id, String code, String name) {
    RoomTypePo po = new RoomTypePo();
    po.setId(id);
    po.setTenantId(1L);
    po.setStoreId(100L);
    po.setCode(code);
    po.setName(name);
    po.setCapacity(20);
    po.setSortOrder(0);
    po.setStatus("ACTIVE");
    return po;
  }
}
