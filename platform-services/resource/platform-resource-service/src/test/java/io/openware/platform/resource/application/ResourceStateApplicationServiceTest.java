package io.openware.platform.resource.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.platform.resource.application.ResourceStateApplicationService.ResourceView;
import io.openware.platform.resource.infra.persistence.mapper.OccupationMapper;
import io.openware.platform.resource.infra.persistence.mapper.ResourceMapper;
import io.openware.platform.resource.infra.persistence.mapper.RoomTypeMapper;
import io.openware.platform.resource.infra.persistence.po.ResourcePo;
import io.openware.platform.resource.infra.persistence.po.RoomTypePo;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * /business/resources 响应契约：B 端房态看板与 C 端选包厢要拿到主图/多图/描述；
 * 历史数据（迁移前创建的包厢）无图无描述时不能报错。
 */
class ResourceStateApplicationServiceTest {

  private ResourceMapper resourceMapper;
  private OccupationMapper occupationMapper;
  private RoomTypeMapper roomTypeMapper;
  private ResourceStateApplicationService service;

  @BeforeEach
  void setUp() {
    resourceMapper = mock(ResourceMapper.class);
    occupationMapper = mock(OccupationMapper.class);
    roomTypeMapper = mock(RoomTypeMapper.class);
    when(roomTypeMapper.selectBatchIds(any())).thenReturn(List.of());
    service = new ResourceStateApplicationService(resourceMapper, occupationMapper, roomTypeMapper);
  }

  @Test
  void listWithStateReturnsImagesMainImageAndDescription() {
    ResourcePo room = room(30L, "V01", "VIP 01");
    room.setImageUrls(List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"));
    room.setMainImageUrl("/api/v1/media-public/b.png");
    room.setDescription("可容纳 12 人");
    when(resourceMapper.selectList(any())).thenReturn(List.of(room));
    when(occupationMapper.selectActiveResourceIds(eq(1L))).thenReturn(List.of());

    List<ResourceView> views = service.listWithState("KTV_ROOM", 1L);

    assertEquals(1, views.size());
    assertEquals(List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), views.get(0).imageUrls());
    assertEquals("/api/v1/media-public/b.png", views.get(0).mainImageUrl());
    assertEquals("可容纳 12 人", views.get(0).description());
    assertTrue(views.get(0).available());
  }

  @Test
  void listWithStateToleratesLegacyRowsWithoutImages() {
    ResourcePo legacy = room(31L, "V02", "老包厢");
    when(resourceMapper.selectList(any())).thenReturn(List.of(legacy));
    when(occupationMapper.selectActiveResourceIds(eq(1L))).thenReturn(List.of());

    List<ResourceView> views = service.listWithState("KTV_ROOM", 1L);

    assertEquals(1, views.size());
    // 空列表 + null：前端走占位图与空描述分支，不因缺字段抛错
    assertTrue(views.get(0).imageUrls().isEmpty());
    assertNull(views.get(0).mainImageUrl());
    assertNull(views.get(0).description());
  }

  @Test
  void listWithStateKeepsStateFlagsAlongsideImages() {
    ResourcePo cleaning = room(32L, "V03", "清洁中包厢");
    cleaning.setCleaningStatus("CLEANING");
    cleaning.setImageUrls(List.of("/api/v1/media-public/clean.png"));
    cleaning.setMainImageUrl("/api/v1/media-public/clean.png");
    when(resourceMapper.selectList(any())).thenReturn(List.of(cleaning));
    when(occupationMapper.selectActiveResourceIds(eq(1L))).thenReturn(List.of());

    List<ResourceView> views = service.listWithState("KTV_ROOM", 1L);

    assertEquals("CLEANING", views.get(0).state());
    assertEquals("清洁中", views.get(0).unavailableReason());
    assertEquals("/api/v1/media-public/clean.png", views.get(0).mainImageUrl());
  }

  @Test
  void viewOfLegacyRoomWithoutImagesDoesNotFail() {
    ResourcePo legacy = room(33L, "V04", "老包厢");
    when(resourceMapper.selectById(33L)).thenReturn(legacy);
    when(occupationMapper.selectActiveResourceIds(eq(1L))).thenReturn(List.of());

    ResourceView view = service.view(33L, 1L);

    assertTrue(view.imageUrls().isEmpty());
    assertNull(view.mainImageUrl());
  }

  /** B 端房态看板要显示包厢区域与房型：区域直接取列，房型按 room_type_id 批量回填名称。 */
  @Test
  void listWithStateReturnsAreaAndRoomTypeName() {
    ResourcePo withType = room(40L, "V10", "VIP 10");
    withType.setAreaName("三楼 A 区");
    withType.setRoomTypeId(31L);
    ResourcePo withoutType = room(41L, "V11", "VIP 11");
    when(resourceMapper.selectList(any())).thenReturn(List.of(withType, withoutType));
    when(occupationMapper.selectActiveResourceIds(eq(1L))).thenReturn(List.of());
    when(roomTypeMapper.selectBatchIds(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    List<ResourceView> views = service.listWithState("KTV_ROOM", 1L);

    assertEquals("三楼 A 区", views.get(0).areaName());
    assertEquals(31L, views.get(0).roomTypeId());
    assertEquals("VIP 大包", views.get(0).roomTypeName());
    assertEquals("VIP", views.get(0).roomTypeCode());
    assertNull(views.get(1).areaName());
    assertNull(views.get(1).roomTypeId());
    assertNull(views.get(1).roomTypeName());
  }

  /** 未设置房型（历史包厢）时视图字段全为 null，不因缺房型报错。 */
  @Test
  void viewToleratesRoomWithoutRoomType() {
    ResourcePo legacy = room(42L, "V12", "老包厢");
    when(resourceMapper.selectById(42L)).thenReturn(legacy);
    when(occupationMapper.selectActiveResourceIds(eq(1L))).thenReturn(List.of());

    ResourceView view = service.view(42L, 1L);

    assertNull(view.roomTypeId());
    assertNull(view.roomTypeName());
    assertNull(view.areaName());
  }

  /** 未装配房型 Mapper（兼容既有装配）时不能因房型查询抛错。 */
  @Test
  void listWithStateWorksWithoutRoomTypeMapper() {
    ResourceStateApplicationService legacyService =
        new ResourceStateApplicationService(resourceMapper, occupationMapper);
    ResourcePo withType = room(43L, "V13", "VIP 13");
    withType.setRoomTypeId(31L);
    when(resourceMapper.selectList(any())).thenReturn(List.of(withType));
    when(occupationMapper.selectActiveResourceIds(eq(1L))).thenReturn(List.of());

    List<ResourceView> views = legacyService.listWithState("KTV_ROOM", 1L);

    assertEquals(1, views.size());
    assertNull(views.get(0).roomTypeName());
  }

  private static RoomTypePo roomType(Long id, String code, String name) {
    RoomTypePo po = new RoomTypePo();
    po.setId(id);
    po.setTenantId(1L);
    po.setStoreId(100L);
    po.setCode(code);
    po.setName(name);
    po.setStatus("ACTIVE");
    return po;
  }

  private static ResourcePo room(Long id, String code, String name) {
    ResourcePo po = new ResourcePo();
    po.setId(id);
    po.setTenantId(1L);
    po.setStoreId(100L);
    po.setResourceType("KTV_ROOM");
    po.setResourceCode(code);
    po.setName(name);
    po.setCapacity(8);
    po.setStatus("ENABLED");
    po.setCleaningStatus("IDLE");
    return po;
  }
}
