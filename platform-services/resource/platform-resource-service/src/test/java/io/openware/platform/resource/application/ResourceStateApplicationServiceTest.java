package io.openware.platform.resource.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.platform.resource.application.ResourceStateApplicationService.ResourceView;
import io.openware.platform.resource.domain.resource.model.Resource;
import io.openware.platform.resource.domain.resource.repository.ResourceRepository;
import io.openware.platform.resource.domain.roomtype.model.RoomType;
import io.openware.platform.resource.domain.roomtype.repository.RoomTypeReader;
import io.openware.platform.resource.domain.occupation.repository.OccupationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 资源运行状态视图。
 *
 * <p>替身是**领域端口**：测试不再构造 MyBatis PO，也不再依赖 mapper 的查询形状
 * （standards/10“测试规则”）。占用判定经 OccupationRepository 端口。
 */
class ResourceStateApplicationServiceTest {

  private ResourceRepository resourceRepository;
  private OccupationRepository occupationRepository;
  private RoomTypeReader roomTypeReader;
  private ResourceStateApplicationService service;

  @BeforeEach
  void setUp() {
    resourceRepository = mock(ResourceRepository.class);
    occupationRepository = mock(OccupationRepository.class);
    roomTypeReader = mock(RoomTypeReader.class);
    when(roomTypeReader.findByIds(any())).thenReturn(List.of());
    service = new ResourceStateApplicationService(resourceRepository, occupationRepository, roomTypeReader);
  }

  @Test
  void listWithStateReturnsAvailableRoom() {
    when(resourceRepository.listEnabledByType(any(), any())).thenReturn(List.of(room(30L, "V01", "VIP 01")));
    when(occupationRepository.findActiveResourceIds(eq(1L))).thenReturn(List.of());

    List<ResourceView> views = service.listWithState("ROOM", 1L);

    assertEquals(1, views.size());
    assertEquals("IDLE", views.getFirst().state());
    assertTrue(views.getFirst().available());
  }

  @Test
  void legacyRoomWithoutMediaFallsBackToEmptyListAndNull() {
    when(resourceRepository.listEnabledByType(any(), any())).thenReturn(List.of(room(31L, "V02", "老包厢")));
    when(occupationRepository.findActiveResourceIds(eq(1L))).thenReturn(List.of());

    List<ResourceView> views = service.listWithState("ROOM", 1L);

    assertEquals(List.of(), views.getFirst().imageUrls());
    assertNull(views.getFirst().mainImageUrl());
    assertNull(views.getFirst().description());
  }

  @Test
  void cleaningRoomIsUnavailableWithReason() {
    Resource cleaning = room(32L, "V03", "清洁中包厢").withCleaning(true, LocalDateTime.now());
    when(resourceRepository.listEnabledByType(any(), any())).thenReturn(List.of(cleaning));
    when(occupationRepository.findActiveResourceIds(eq(1L))).thenReturn(List.of());

    List<ResourceView> views = service.listWithState("ROOM", 1L);

    assertEquals("CLEANING", views.getFirst().state());
    assertEquals("清洁中", views.getFirst().unavailableReason());
    assertFalse(views.getFirst().available());
  }

  @Test
  void occupiedRoomIsUnavailableWithReason() {
    when(resourceRepository.findById(33L)).thenReturn(Optional.of(room(33L, "V04", "老包厢")));
    when(occupationRepository.findActiveResourceIds(eq(1L))).thenReturn(List.of(33L));

    ResourceView view = service.view(33L, 1L);

    assertEquals("OCCUPIED", view.state());
    assertEquals("使用中", view.unavailableReason());
  }

  @Test
  void roomTypeSnapshotIsFilledInBulk() {
    Resource withType = room(40L, "V10", "VIP 10", 31L);
    Resource withoutType = room(41L, "V11", "VIP 11");
    when(resourceRepository.listEnabledByType(any(), any())).thenReturn(List.of(withType, withoutType));
    when(occupationRepository.findActiveResourceIds(eq(1L))).thenReturn(List.of());
    when(roomTypeReader.findByIds(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    List<ResourceView> views = service.listWithState("ROOM", 1L);

    assertEquals("VIP 大包", views.get(0).roomTypeName());
    assertEquals("VIP", views.get(0).roomTypeCode());
    // 未设置房型的资源三个字段均为 null（前端按「未设置」展示）
    assertNull(views.get(1).roomTypeName());
    assertNull(views.get(1).roomTypeCode());
  }

  @Test
  void singleViewWithoutRoomTypeKeepsFieldsNull() {
    when(resourceRepository.findById(42L)).thenReturn(Optional.of(room(42L, "V12", "老包厢")));
    when(occupationRepository.findActiveResourceIds(eq(1L))).thenReturn(List.of());

    ResourceView view = service.view(42L, 1L);

    assertNull(view.roomTypeName());
    assertNull(view.roomTypeCode());
  }

  /** 未装配房型读取（兼容既有装配）时不能因房型查询抛错。 */
  @Test
  void listWithStateWorksWithoutRoomTypeReader() {
    ResourceStateApplicationService withoutRoomTypes =
        new ResourceStateApplicationService(resourceRepository, occupationRepository);
    when(resourceRepository.listEnabledByType(any(), any())).thenReturn(List.of(room(43L, "V13", "VIP 13", 31L)));
    when(occupationRepository.findActiveResourceIds(eq(1L))).thenReturn(List.of());

    List<ResourceView> views = withoutRoomTypes.listWithState("ROOM", 1L);

    assertEquals(1, views.size());
    assertNull(views.getFirst().roomTypeName());
    assertNotNull(views.getFirst().name());
  }

  private static RoomType roomType(Long id, String code, String name) {
    return new RoomType(id, 1L, 1L, code, name, 10, List.of(), null, 100L, 0L, 1, RoomType.STATUS_ACTIVE);
  }

  private static Resource room(Long id, String code, String name) {
    return room(id, code, name, null);
  }

  private static Resource room(Long id, String code, String name, Long roomTypeId) {
    return new Resource(id, 1L, 1L, "ROOM", code, name, "A 区", null, 10, roomTypeId,
        Resource.STATUS_ENABLED, Resource.CLEANING_IDLE, null, List.of(), null, null,
        null, null, null, null);
  }
}
