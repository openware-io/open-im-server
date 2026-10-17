package io.openware.platform.resource.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.platform.resource.application.ResourceCommandApplicationService;
import io.openware.platform.resource.application.ResourceQueryApplicationService;
import io.openware.platform.resource.application.RoomTypeApplicationService;
import io.openware.platform.resource.domain.resource.model.Resource;
import io.openware.platform.resource.domain.resource.repository.ResourceRepository;
import io.openware.platform.resource.domain.roomtype.model.RoomType;
import io.openware.platform.resource.domain.roomtype.repository.RoomTypeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 包厢创建/更新的图片、描述、区域、房型：落库字段、主图规则、描述/区域超长 400（BusinessException）、
 * 房型必须属于本门店、历史无图不报错。
 *
 * <p><b>分层后的断言归属</b>：此前本文件大量断言 {@code LambdaUpdateWrapper} 的 SET 子句与绑定参数，
 * 那是控制器自己下发 SQL 时的产物。控制器迁到端口后，"提交了哪些列、null 是否真的进 SET、JSON 列是否
 * 带 typeHandler"由 {@link io.openware.platform.resource.infra.persistence.repository.ResourceRepositoryAdapterTest}
 * 在**真正写列的那一层**验证；本文件只断言**控制器职责**：参数归一、校验、以及调用了哪个端口方法。
 *
 * <p>同理，列表的 {@code created_at} 闭区间过滤已下推到仓储端口，其 SQL 口径不在控制器测试范围内。
 */
class ResourceControllerTest {

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
    controller = new ResourceController(new ResourceCommandApplicationService(resourceRepository,
        resourceQuery, roomTypeService), resourceQuery);
    when(resourceQuery.enrichWithRoomTypes(any())).thenAnswer(i -> i.getArgument(0));
    when(resourceQuery.listEnabled(any(), any())).thenReturn(List.of());
    TenantContextHolder.set(new TenantContext(1L, null, 100L, 1L, 1, List.of("resource.manage")));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /** 创建时端口返回带主键的资源（真实适配器在 insert 后回填，测试替身等价模拟）。 */
  private void savingReturnsWithId(long id) {
    when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> withId(invocation.getArgument(0), id));
  }

  @Test
  void createStoresImagesMainImageAndDescription() {
    savingReturnsWithId(7L);

    var created = controller.create(new ResourceController.CreateResourceRequest(
        1L, 100L, "KTV_ROOM", "V01", "VIP 01", null, 12, null,
        List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), "/api/v1/media-public/b.png", "  豪华大包  "));

    assertEquals(List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), created.imageUrls());
    assertEquals("/api/v1/media-public/b.png", created.mainImageUrl());
    assertEquals("豪华大包", created.description());
    assertEquals(100L, created.storeId());
    ArgumentCaptor<Resource> captor = ArgumentCaptor.forClass(Resource.class);
    verify(resourceRepository).save(captor.capture());
    assertEquals(Resource.STATUS_ENABLED, captor.getValue().status(), "新建资源必须是启用态");
  }

  /** 区域与房型：落库 area_name/room_type_id，并在响应里回填房型编码/名称/单价（后台前端据此展示「区域/房型」）。 */
  @Test
  void createStoresAreaAndRoomTypeAndReturnsRoomTypeName() {
    savingReturnsWithId(7L);
    when(roomTypeRepository.findInStore(1L, 100L, 31L))
        .thenReturn(Optional.of(roomType(31L, "VIP", "VIP 大包", 20000L, 6000L)));
    when(resourceQuery.enrichWithRoomTypes(any())).thenAnswer(i ->
        ((List<Resource>) i.getArgument(0)).stream()
            .map(r -> ResourceQueryApplicationService.applyRoomType(r, roomType(31L, "VIP", "VIP 大包", 20000L, 6000L)))
            .toList());

    var created = controller.create(new ResourceController.CreateResourceRequest(
        1L, 100L, "KTV_ROOM", "V07", "VIP 07", "  三楼 A 区  ", 20, 31L, null, null, null));

    assertEquals("三楼 A 区", created.areaName());
    assertEquals(31L, created.roomTypeId());
    assertEquals("VIP", created.roomTypeCode());
    assertEquals("VIP 大包", created.roomTypeName());
    assertEquals(20000L, created.roomTypeUnitPrice());
    assertEquals(6000L, created.roomTypeServerUnitPrice());
    ArgumentCaptor<Resource> captor = ArgumentCaptor.forClass(Resource.class);
    verify(resourceRepository).save(captor.capture());
    assertEquals(31L, captor.getValue().roomTypeId());
    assertEquals("三楼 A 区", captor.getValue().areaName());
  }

  @Test
  void createDefaultsMainImageToFirstWhenNotSpecified() {
    savingReturnsWithId(7L);

    var created = controller.create(new ResourceController.CreateResourceRequest(
        1L, 100L, "KTV_ROOM", "V02", "VIP 02", null, 8, null,
        List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), null, null));

    assertEquals("/api/v1/media-public/a.png", created.mainImageUrl());
    assertNull(created.description());
  }

  @Test
  void createWithoutImagesKeepsEmptyListAndNullMainImage() {
    savingReturnsWithId(7L);

    var created = controller.create(new ResourceController.CreateResourceRequest(
        1L, 100L, "KTV_ROOM", "V03", "VIP 03", null, 8, null, null, null, null));

    assertEquals(List.of(), created.imageUrls());
    assertNull(created.mainImageUrl());
    assertNull(created.areaName());
    assertNull(created.roomTypeId());
  }

  @Test
  void createRejectsMoreThanNineImagesWithoutInserting() {
    List<String> urls = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      urls.add("/img/" + i + ".png");
    }

    BusinessException error = assertThrows(BusinessException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V04", "VIP 04", null, 8, null,
            urls, null, null)));

    assertEquals("RESOURCE_INVALID", error.getCode());
    assertEquals("包厢图片最多 9 张，当前 10 张", error.getMessage());
    verify(resourceRepository, never()).save(any(Resource.class));
  }

  @Test
  void createRejectsMainImageOutsideList() {
    BusinessException error = assertThrows(BusinessException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V05", "VIP 05", null, 8, null,
            List.of("/a.png"), "/b.png", null)));

    assertEquals("包厢主图必须是已上传图片中的一张", error.getMessage());
    verify(resourceRepository, never()).save(any(Resource.class));
  }

  @Test
  void createRejectsOverlongDescription() {
    BusinessException error = assertThrows(BusinessException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V06", "VIP 06", null, 8, null,
            null, null, "包".repeat(256))));

    assertEquals("包厢描述长度不能超过 255 个字符", error.getMessage());
    verify(resourceRepository, never()).save(any(Resource.class));
  }

  /** 区域列是 varchar(64)：超长必须在落库前 400，而不是把数据库截断/报错暴露给调用方。 */
  @Test
  void createRejectsOverlongAreaName() {
    ApiException error = assertThrows(ApiException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V08", "VIP 08", "区".repeat(65),
            8, null, null, null, null)));

    assertEquals(400, error.getStatus());
    assertEquals("AREA_NAME_TOO_LONG", error.getCode());
    verify(resourceRepository, never()).save(any(Resource.class));
  }

  /**
   * 创建时 roomTypeId=0 与编辑清空同一语义：都表示「不指定房型」，落库 null，不再抛 ROOM_TYPE_INVALID。
   * 前端创建按 null、编辑清空按 0 提交，后端必须两端一致。
   */
  @Test
  void createTreatsZeroRoomTypeIdAsUnspecified() {
    savingReturnsWithId(7L);

    var created = controller.create(new ResourceController.CreateResourceRequest(
        1L, 100L, "KTV_ROOM", "V10", "VIP 10", null, 8, 0L, null, null, null));

    assertNull(created.roomTypeId());
    assertNull(created.roomTypeName());
    ArgumentCaptor<Resource> captor = ArgumentCaptor.forClass(Resource.class);
    verify(resourceRepository).save(captor.capture());
    assertNull(captor.getValue().roomTypeId(), "0 必须落库为 null（回退门店级单价）");
    verify(roomTypeRepository, never()).findInStore(any(), any(), anyLong());
  }

  /** 房型必须属于本门店：跨门店/不存在的房型 id 直接 400，避免包厢挂到别的门店房型上取错价。 */
  @Test
  void createRejectsRoomTypeOutsideCurrentStore() {
    when(roomTypeRepository.findInStore(1L, 100L, 99L)).thenReturn(Optional.empty());

    ApiException error = assertThrows(ApiException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V09", "VIP 09", null, 8, 99L,
            null, null, null)));

    assertEquals(400, error.getStatus());
    assertEquals("ROOM_TYPE_INVALID", error.getCode());
    verify(resourceRepository, never()).save(any(Resource.class));
  }

  @Test
  void createStillRejectsDuplicateResourceCode() {
    when(resourceRepository.countByResourceCode(any(), any(), any(), any())).thenReturn(1L);

    ApiException error = assertThrows(ApiException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V01", "VIP 01", null, 8, null,
            null, null, null)));

    assertEquals(409, error.getStatus());
    assertEquals("RESOURCE_CODE_EXISTS", error.getCode());
    verify(resourceRepository, never()).save(any(Resource.class));
  }

  @Test
  void updateReplacesImagesDescriptionAndCapacity() {
    when(resourceRepository.findById(7L)).thenReturn(Optional.of(existing(7L, "/api/v1/media-public/old.png", "旧描述")));
    when(resourceRepository.updateDetails(anyLong(), any(), any(), any(), any())).thenReturn(true);
    when(resourceRepository.updateMedia(anyLong(), any(), any())).thenReturn(true);

    var updated = controller.update(7L, new ResourceController.UpdateResourceRequest(
        "  新名字  ", "  二楼 B 区  ", 16, null,
        List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), null, "  新描述  "));

    assertEquals("新名字", updated.name());
    assertEquals("二楼 B 区", updated.areaName());
    assertEquals(16, updated.capacity());
    assertEquals(List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), updated.imageUrls());
    assertEquals("/api/v1/media-public/a.png", updated.mainImageUrl());
    assertEquals("新描述", updated.description());
    // 基础信息与图片是两组列，各走自己的具名端口方法（空值也必须真的写库，见适配器测试）。
    verify(resourceRepository).updateDetails(7L, "新名字", "二楼 B 区", 16, "新描述");
    verify(resourceRepository).updateMedia(7L,
        List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), "/api/v1/media-public/a.png");
  }

  /** 房型切换与清空：传新 id 换房型，传 0 清空房型（回到门店级单价）。 */
  @Test
  void updateSwitchesAndClearsRoomType() {
    Resource current = withRoomType(existing(14L, null, null), 31L);
    when(resourceRepository.findById(14L)).thenReturn(Optional.of(current));
    when(resourceRepository.updateRoomTypeBinding(anyLong(), anyLong())).thenReturn(true);
    when(resourceRepository.clearRoomTypeBinding(anyLong())).thenReturn(true);
    when(roomTypeRepository.findInStore(1L, 100L, 32L))
        .thenReturn(Optional.of(roomType(32L, "LARGE", "大包", 15000L, null)));
    when(resourceQuery.enrichWithRoomTypes(any())).thenAnswer(i ->
        ((List<Resource>) i.getArgument(0)).stream()
            .map(r -> r.roomTypeId() == null ? r
                : ResourceQueryApplicationService.applyRoomType(r, roomType(32L, "LARGE", "大包", 15000L, null)))
            .toList());

    var switched = controller.update(14L,
        new ResourceController.UpdateResourceRequest(null, null, null, 32L, null, null, null));

    assertEquals(32L, switched.roomTypeId());
    assertEquals("大包", switched.roomTypeName());

    var cleared = controller.update(14L,
        new ResourceController.UpdateResourceRequest(null, null, null, 0L, null, null, null));

    assertNull(cleared.roomTypeId());
    assertNull(cleared.roomTypeName(), "清空房型后响应里不能残留上一次的房型名");
    // 换绑与解绑走两个不同的具名方法：解绑必须能被表达为"清空这一列"。
    verify(resourceRepository).updateRoomTypeBinding(14L, 32L);
    verify(resourceRepository).clearRoomTypeBinding(14L);
    verify(roomTypeRepository, never()).findInStore(any(), any(), eq(0L));
  }

  /** 部分更新语义：未提交的基础信息不落写（不能把没传的字段一起覆盖成 NULL）。 */
  @Test
  void updateWithoutOtherFieldsOnlyTouchesSubmittedGroups() {
    Resource current = withRoomType(existing(31L, "/api/v1/media-public/old.png", "旧描述"), 31L);
    when(resourceRepository.findById(31L)).thenReturn(Optional.of(current));
    when(resourceRepository.clearRoomTypeBinding(anyLong())).thenReturn(true);

    controller.update(31L,
        new ResourceController.UpdateResourceRequest(null, null, null, 0L, null, null, null));

    verify(resourceRepository).clearRoomTypeBinding(31L);
    verify(resourceRepository, never()).updateDetails(anyLong(), any(), any(), any(), any());
    verify(resourceRepository, never()).updateMedia(anyLong(), any(), any());
    verify(resourceRepository, never()).save(any(Resource.class));
  }

  /** 可清空字段：areaName 传空白、图片传空列表、描述传空白，都必须作为"提交了空值"落库。 */
  @Test
  void updateClearsAreaMainImageAndDescription() {
    Resource current = withArea(existing(32L, "/api/v1/media-public/old.png", "旧描述"), "三楼 A 区");
    when(resourceRepository.findById(32L)).thenReturn(Optional.of(current));
    when(resourceRepository.updateDetails(anyLong(), any(), any(), any(), any())).thenReturn(true);
    when(resourceRepository.updateMedia(anyLong(), any(), any())).thenReturn(true);

    var updated = controller.update(32L, new ResourceController.UpdateResourceRequest(
        null, "   ", null, null, List.of(), null, "   "));

    assertNull(updated.areaName());
    assertEquals(List.of(), updated.imageUrls());
    assertNull(updated.mainImageUrl());
    assertNull(updated.description());
    verify(resourceRepository).updateDetails(32L, "包厢 32", null, 8, null);
    verify(resourceRepository).updateMedia(32L, List.of(), null);
  }

  @Test
  void updateWithoutAreaFieldKeepsExistingArea() {
    Resource current = withArea(existing(15L, null, null), "一楼大堂");
    when(resourceRepository.findById(15L)).thenReturn(Optional.of(current));
    when(resourceRepository.updateDetails(anyLong(), any(), any(), any(), any())).thenReturn(true);

    var updated = controller.update(15L,
        new ResourceController.UpdateResourceRequest("改名", null, null, null, null, null, null));

    assertEquals("一楼大堂", updated.areaName());
    // 未提交 areaName 时必须回传原值，端口才能把它写回原样（不能被覆盖成 NULL）。
    verify(resourceRepository).updateDetails(15L, "改名", "一楼大堂", 8, null);
  }

  @Test
  void updateWithoutImageFieldKeepsExistingImages() {
    when(resourceRepository.findById(9L)).thenReturn(Optional.of(existing(9L, "/api/v1/media-public/old.png", "旧描述")));
    when(resourceRepository.updateDetails(anyLong(), any(), any(), any(), any())).thenReturn(true);

    var updated = controller.update(9L, new ResourceController.UpdateResourceRequest(
        "改名", null, null, null, null, null, null));

    assertEquals("改名", updated.name());
    assertEquals(List.of("/api/v1/media-public/old.png"), updated.imageUrls());
    assertEquals("/api/v1/media-public/old.png", updated.mainImageUrl());
    assertEquals("旧描述", updated.description());
    verify(resourceRepository, never()).updateMedia(anyLong(), any(), any());
  }

  @Test
  void updateRejectsMoreThanNineImages() {
    when(resourceRepository.findById(10L)).thenReturn(Optional.of(existing(10L, null, null)));
    List<String> urls = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      urls.add("/img/" + i + ".png");
    }

    BusinessException error = assertThrows(BusinessException.class, () -> controller.update(
        10L, new ResourceController.UpdateResourceRequest(null, null, null, null, urls, null, null)));

    assertEquals("包厢图片最多 9 张，当前 10 张", error.getMessage());
    verify(resourceRepository, never()).updateMedia(anyLong(), any(), any());
  }

  @Test
  void updateRejectsOverlongDescription() {
    when(resourceRepository.findById(11L)).thenReturn(Optional.of(existing(11L, null, null)));

    BusinessException error = assertThrows(BusinessException.class, () -> controller.update(
        11L, new ResourceController.UpdateResourceRequest(null, null, null, null, null, null, "包".repeat(256))));

    assertEquals("包厢描述长度不能超过 255 个字符", error.getMessage());
    verify(resourceRepository, never()).updateDetails(anyLong(), any(), any(), any(), any());
  }

  @Test
  void updateRejectsOverlongAreaName() {
    when(resourceRepository.findById(16L)).thenReturn(Optional.of(existing(16L, null, null)));

    ApiException error = assertThrows(ApiException.class, () -> controller.update(
        16L, new ResourceController.UpdateResourceRequest(null, "区".repeat(65), null, null, null, null, null)));

    assertEquals(400, error.getStatus());
    assertEquals("AREA_NAME_TOO_LONG", error.getCode());
    verify(resourceRepository, never()).updateDetails(anyLong(), any(), any(), any(), any());
  }

  @Test
  void updateMissingResourceIs404() {
    when(resourceRepository.findById(404L)).thenReturn(Optional.empty());

    ApiException error = assertThrows(ApiException.class, () -> controller.update(
        404L, new ResourceController.UpdateResourceRequest("x", null, null, null, null, null, null)));

    assertEquals(404, error.getStatus());
    assertEquals("RESOURCE_NOT_FOUND", error.getCode());
  }

  @Test
  void updateRejectsResourceOfAnotherStore() {
    Resource other = withStore(existing(12L, null, null), 999L);
    when(resourceRepository.findById(12L)).thenReturn(Optional.of(other));

    ApiException error = assertThrows(ApiException.class, () -> controller.update(
        12L, new ResourceController.UpdateResourceRequest("x", null, null, null, null, null, null)));

    assertEquals(403, error.getStatus());
    assertEquals("STORE_SCOPE_DENIED", error.getCode());
    verify(resourceRepository, never()).updateDetails(anyLong(), any(), any(), any(), any());
  }

  @Test
  void updateWithoutManagePermissionIs403() {
    TenantContextHolder.set(new TenantContext(1L, null, 100L, 1L, 1, List.of("resource.view")));

    ApiException error = assertThrows(ApiException.class, () -> controller.update(
        13L, new ResourceController.UpdateResourceRequest("x", null, null, null, null, null, null)));

    assertEquals(403, error.getStatus());
    assertEquals("PERMISSION_DENIED", error.getCode());
  }

  /** 列表委托查询用例，并把房型快照映射进响应。 */
  @Test
  void listReturnsRoomTypeNameForRowsWithRoomType() {
    Resource withType = withRoomType(withArea(existing(21L, null, null), "三楼 A 区"), 31L);
    when(resourceQuery.listEnabled("KTV_ROOM", 100L)).thenAnswer(i ->
        ((List<Resource>) List.of(ResourceQueryApplicationService.applyRoomType(withType,
            roomType(31L, "VIP", "VIP 大包", 20000L, 6000L)), existing(22L, null, null))));

    List<io.openware.platform.resource.api.dto.ResourceView> rows = controller.list("KTV_ROOM", 100L, null, null);

    assertEquals("VIP", rows.get(0).roomTypeCode());
    assertEquals("VIP 大包", rows.get(0).roomTypeName());
    assertEquals("三楼 A 区", rows.get(0).areaName());
    assertNull(rows.get(1).roomTypeName());
  }

  /** 历史数据无图无描述：查询原样返回空值，不因新字段报错。 */
  @Test
  void listReturnsRowsWithNullMediaForHistoricalData() {
    when(resourceQuery.listEnabled("KTV_ROOM", 100L)).thenReturn(List.of(existing(20L, null, null)));

    var rows = controller.list("KTV_ROOM", 100L, null, null);

    assertEquals(1, rows.size());
    assertEquals(List.of(), rows.get(0).imageUrls());
    assertNull(rows.get(0).mainImageUrl());
    assertNull(rows.get(0).description());
    assertNull(rows.get(0).areaName());
    assertNull(rows.get(0).roomTypeName());
  }

  /** 时间区间解析留在控制器（入站参数口径）；合法区间下推给查询用例的区间重载。 */
  @Test
  void listPassesClosedCreatedAtRange() {
    controller.list("KTV_ROOM", 100L, "2026-09-01", "2026-09-30");

    ArgumentCaptor<java.time.LocalDateTime> from = ArgumentCaptor.forClass(java.time.LocalDateTime.class);
    ArgumentCaptor<java.time.LocalDateTime> to = ArgumentCaptor.forClass(java.time.LocalDateTime.class);
    verify(resourceQuery).listEnabledInRange(eq("KTV_ROOM"), eq(100L), from.capture(), to.capture());
    assertEquals(java.time.LocalDateTime.of(2026, 9, 1, 0, 0), from.getValue());
    assertEquals(java.time.LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000), to.getValue());
  }

  /** 不传时间参数：不设边界（空区间 = 不筛，不是「筛 0 条」）。 */
  @Test
  void listWithoutTimeParamsStillQueries() {
    controller.list("KTV_ROOM", 100L, null, null);

    verify(resourceQuery).listEnabled("KTV_ROOM", 100L);
  }

  /** from > to → 400 {@code TIME_RANGE_INVALID}（与全仓其它列表同一错误码），且不查库。 */
  @Test
  void listRejectsInvertedRangeWith400() {
    ApiException failure = assertThrows(ApiException.class,
        () -> controller.list(null, null, "2026-09-30", "2026-09-01"));

    assertEquals(400, failure.getStatus());
    assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
    assertEquals(TimeRangeParams.MESSAGE_TIME_RANGE_INVALID, failure.getMessage());
    verify(resourceQuery, never()).listEnabled(any(), any());
  }

  @Test
  void listRejectsMalformedTimeWith400() {
    ApiException failure = assertThrows(ApiException.class,
        () -> controller.list(null, null, "2026/09/01", null));

    assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
    verify(resourceQuery, never()).listEnabled(any(), any());
  }

  // —— 领域模型夹具（不可变 record，按需整体替换） ——

  private static RoomType roomType(Long id, String code, String name, Long unitPrice, Long serverUnitPrice) {
    return new RoomType(id, 1L, 100L, code, name, 10, List.of(), null, unitPrice, serverUnitPrice, 0,
        RoomType.STATUS_ACTIVE);
  }

  private static Resource existing(Long id, String imageUrl, String description) {
    return new Resource(id, 1L, 100L, "KTV_ROOM", "V" + id, "包厢 " + id, null, null, 8, null,
        Resource.STATUS_ENABLED, Resource.CLEANING_IDLE, null,
        imageUrl == null ? null : List.of(imageUrl), imageUrl, description, null, null, null, null);
  }

  private static Resource withId(Resource r, Long id) {
    return new Resource(id, r.tenantId(), r.storeId(), r.resourceType(), r.resourceCode(), r.name(),
        r.areaName(), r.parentId(), r.capacity(), r.roomTypeId(), r.status(), r.cleaningStatus(),
        r.cleaningStartedAt(), r.imageUrls(), r.mainImageUrl(), r.description(), r.roomTypeCode(),
        r.roomTypeName(), r.roomTypeUnitPrice(), r.roomTypeServerUnitPrice());
  }

  private static Resource withRoomType(Resource r, Long roomTypeId) {
    return new Resource(r.id(), r.tenantId(), r.storeId(), r.resourceType(), r.resourceCode(), r.name(),
        r.areaName(), r.parentId(), r.capacity(), roomTypeId, r.status(), r.cleaningStatus(),
        r.cleaningStartedAt(), r.imageUrls(), r.mainImageUrl(), r.description(), r.roomTypeCode(),
        r.roomTypeName(), r.roomTypeUnitPrice(), r.roomTypeServerUnitPrice());
  }

  private static Resource withArea(Resource r, String areaName) {
    return new Resource(r.id(), r.tenantId(), r.storeId(), r.resourceType(), r.resourceCode(), r.name(),
        areaName, r.parentId(), r.capacity(), r.roomTypeId(), r.status(), r.cleaningStatus(),
        r.cleaningStartedAt(), r.imageUrls(), r.mainImageUrl(), r.description(), r.roomTypeCode(),
        r.roomTypeName(), r.roomTypeUnitPrice(), r.roomTypeServerUnitPrice());
  }

  private static Resource withStore(Resource r, Long storeId) {
    return new Resource(r.id(), r.tenantId(), storeId, r.resourceType(), r.resourceCode(), r.name(),
        r.areaName(), r.parentId(), r.capacity(), r.roomTypeId(), r.status(), r.cleaningStatus(),
        r.cleaningStartedAt(), r.imageUrls(), r.mainImageUrl(), r.description(), r.roomTypeCode(),
        r.roomTypeName(), r.roomTypeUnitPrice(), r.roomTypeServerUnitPrice());
  }
}
