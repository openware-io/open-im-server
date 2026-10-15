package io.openware.platform.resource.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.resource.application.ResourceQueryApplicationService;
import io.openware.platform.resource.application.RoomTypeApplicationService;
import io.openware.platform.resource.domain.resource.model.Resource;
import io.openware.platform.resource.domain.resource.repository.ResourceRepository;
import io.openware.platform.resource.domain.roomtype.model.RoomType;
import io.openware.platform.resource.domain.roomtype.repository.RoomTypeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 房型解绑的缺陷链路回归（控制器层）。
 *
 * <p>缺陷：{@code PUT /api/v1/admin/resources/{id}} body {@code {"roomTypeId":0}} 解绑房型后，
 * {@code DELETE /api/v1/admin/resources/types/{id}} 必须不再 409 {@code ROOM_TYPE_IN_USE}。
 *
 * <p>根因是清空写 null，而 MyBatis-Plus 默认的 NOT_NULL 更新策略会把 null 列从 UPDATE 中剔除，
 * 于是解绑静默失效。因此本测试锁定**语义**：解绑必须走端口的具名方法
 * {@link ResourceRepository#clearRoomTypeBinding}，并且**不得**走整体保存
 * （{@code save} 无法表达"清空这一列"）。
 *
 * <p>「清空真的写进 SQL」这一层保真断言在
 * {@link io.openware.platform.resource.infra.persistence.repository.ResourceRepositoryAdapterTest}
 * 中验证——那里有真适配器，能检查生成的 SET 子句。分层后两边都更聚焦。
 */
class RoomTypeUnbindRegressionTest {

  private static final long TENANT_ID = 1L;
  private static final long STORE_ID = 100L;
  private static final long RESOURCE_ID = 7L;
  private static final long ROOM_TYPE_ID = 31L;

  private ResourceRepository resourceRepository;
  private ResourceQueryApplicationService resourceQuery;
  private RoomTypeRepository roomTypeRepository;
  private RoomTypeApplicationService roomTypeService;
  private ResourceController controller;

  @BeforeEach
  void setUp() {
    resourceRepository = mock(ResourceRepository.class);
    resourceQuery = mock(ResourceQueryApplicationService.class);
    roomTypeRepository = mock(RoomTypeRepository.class);
    roomTypeService = new RoomTypeApplicationService(roomTypeRepository, resourceRepository);
    controller = new ResourceController(resourceRepository, resourceQuery, roomTypeService);
    when(resourceQuery.enrichWithRoomTypes(any())).thenAnswer(i -> i.getArgument(0));
    TenantContextHolder.set(new TenantContext(TENANT_ID, null, STORE_ID, 1L, 1, List.of("resource.manage")));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /** 解绑前 409 → PUT roomTypeId=0 后房型不再被引用 → DELETE 成功。 */
  @Test
  void unbindRoomTypeMakesRoomTypeDeletable() {
    when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource(ROOM_TYPE_ID)));
    // 解绑前：该房型仍被 1 个包厢引用 → 409
    when(resourceRepository.countByRoomTypeId(ROOM_TYPE_ID)).thenReturn(1L);
    when(roomTypeRepository.findById(ROOM_TYPE_ID))
        .thenReturn(Optional.of(roomType(ROOM_TYPE_ID, "VIP", "VIP 大包")));

    ApiException inUse = assertThrows(ApiException.class, () -> roomTypeService.delete(ROOM_TYPE_ID));
    assertEquals(409, inUse.getStatus());
    assertEquals("ROOM_TYPE_IN_USE", inUse.getCode());

    var view = controller.update(RESOURCE_ID,
        new ResourceController.UpdateResourceRequest(null, null, null, 0L, null, null, null));

    assertNull(view.roomTypeId(), "响应里的房型必须已清空");
    // 关键：解绑经具名端口方法，而不是整体保存
    verify(resourceRepository).clearRoomTypeBinding(RESOURCE_ID);
    verify(resourceRepository, never()).save(any(Resource.class));

    // 解绑后：引用计数归零 → 可删除
    when(resourceRepository.countByRoomTypeId(ROOM_TYPE_ID)).thenReturn(0L);
    when(roomTypeRepository.deleteById(ROOM_TYPE_ID)).thenReturn(true);
    roomTypeService.delete(ROOM_TYPE_ID);

    verify(roomTypeRepository).deleteById(ROOM_TYPE_ID);
  }

  /** 反例保护：还有别的包厢绑着该房型时，DELETE 依旧 409（修的是「真清空」，不是放行删除）。 */
  @Test
  void deleteStillRejectsWhenAnotherRoomBindsTheRoomType() {
    when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource(ROOM_TYPE_ID)));
    when(roomTypeRepository.findById(ROOM_TYPE_ID))
        .thenReturn(Optional.of(roomType(ROOM_TYPE_ID, "VIP", "VIP 大包")));

    controller.update(RESOURCE_ID,
        new ResourceController.UpdateResourceRequest(null, null, null, 0L, null, null, null));

    // 另一个包厢仍绑定 → 计数非零 → 409，且不得删除
    when(resourceRepository.countByRoomTypeId(ROOM_TYPE_ID)).thenReturn(1L);
    ApiException error = assertThrows(ApiException.class, () -> roomTypeService.delete(ROOM_TYPE_ID));
    assertEquals(409, error.getStatus());
    assertEquals("ROOM_TYPE_IN_USE", error.getCode());
    verify(roomTypeRepository, never()).deleteById(anyLong());
  }

  /** 换到另一房型时走具名的绑定方法，同样不得整体保存。 */
  @Test
  void rebindingRoomTypeUsesNamedPort() {
    when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource(ROOM_TYPE_ID)));
    when(roomTypeRepository.findInStore(TENANT_ID, STORE_ID, 41L))
        .thenReturn(Optional.of(roomType(41L, "BIG", "大包")));

    var view = controller.update(RESOURCE_ID,
        new ResourceController.UpdateResourceRequest(null, null, null, 41L, null, null, null));

    assertEquals(41L, view.roomTypeId());
    verify(resourceRepository).updateRoomTypeBinding(RESOURCE_ID, 41L);
    verify(resourceRepository, never()).clearRoomTypeBinding(anyLong());
    verify(resourceRepository, never()).save(any(Resource.class));
  }

  private static Resource resource(Long roomTypeId) {
    return new Resource(RESOURCE_ID, TENANT_ID, STORE_ID, "KTV_ROOM", "V07", "包厢 7", "A 区", null, 8,
        roomTypeId, Resource.STATUS_ENABLED, Resource.CLEANING_IDLE, null, List.of(), null, null,
        null, null, null, null);
  }

  private static RoomType roomType(Long id, String code, String name) {
    return new RoomType(id, TENANT_ID, STORE_ID, code, name, 10, List.of(), null, 100L, 0L, 1,
        RoomType.STATUS_ACTIVE);
  }
}
