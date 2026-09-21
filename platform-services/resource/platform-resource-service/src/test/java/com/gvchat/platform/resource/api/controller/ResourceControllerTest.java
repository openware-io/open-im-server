package com.gvchat.platform.resource.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.platform.resource.application.RoomTypeApplicationService;
import com.gvchat.platform.resource.infra.persistence.mapper.ResourceMapper;
import com.gvchat.platform.resource.infra.persistence.po.ResourcePo;
import com.gvchat.platform.resource.infra.persistence.po.RoomTypePo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 包厢创建/更新的图片、描述、区域、房型：落库字段、主图规则、描述/区域超长 400（BusinessException）、
 * 房型必须属于本门店、历史无图不报错。
 *
 * <p>编辑走显式 {@code LambdaUpdateWrapper}（不再用默认 NOT_NULL 策略的 updateById），
 * 故更新类用例断言的是「真正下发的 SET 子句与绑定参数」，而不是 PO 上的字段值。
 */
class ResourceControllerTest {

  /** SET 子句里的片段：{@code room_type_id=#{ew.paramNameValuePairs.MPGENVAL1}}（JSON 列还会带 typeHandler）。 */
  private static final Pattern SET_FRAGMENT =
      Pattern.compile("([a-z_]+)=#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)([^}]*)}");

  private ResourceMapper resourceMapper;
  private RoomTypeApplicationService roomTypeService;
  private ResourceController controller;

  @BeforeEach
  void setUp() {
    // MyBatis-Plus 的列名解析是懒求值的：纯单元测试（无 Spring/MyBatis 上下文）里必须先注册实体的
    // TableInfo，LambdaUpdateWrapper.set(Po::getXxx, ...) 才能把方法引用解析成列名。
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ResourcePo.class);
    resourceMapper = mock(ResourceMapper.class);
    roomTypeService = mock(RoomTypeApplicationService.class);
    controller = new ResourceController(resourceMapper, roomTypeService);
    when(roomTypeService.listByIds(any())).thenReturn(List.of());
    TenantContextHolder.set(new TenantContext(1L, null, 100L, 1L, 1, List.of("resource.manage")));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void createStoresImagesMainImageAndDescription() {
    when(resourceMapper.selectCount(any())).thenReturn(0L);
    when(resourceMapper.insert(any(ResourcePo.class))).thenReturn(1);

    ResourcePo created = controller.create(new ResourceController.CreateResourceRequest(
        1L, 100L, "KTV_ROOM", "V01", "VIP 01", null, 12, null,
        List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), "/api/v1/media-public/b.png", "  豪华大包  "));

    assertEquals(List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), created.getImageUrls());
    assertEquals("/api/v1/media-public/b.png", created.getMainImageUrl());
    assertEquals("豪华大包", created.getDescription());
    assertEquals(100L, created.getStoreId());
    verify(resourceMapper).insert(created);
  }

  /** 区域与房型：落库 area_name/room_type_id，并在响应里回填房型编码/名称/单价（后台前端据此展示「区域/房型」）。 */
  @Test
  void createStoresAreaAndRoomTypeAndReturnsRoomTypeName() {
    when(resourceMapper.selectCount(any())).thenReturn(0L);
    when(resourceMapper.insert(any(ResourcePo.class))).thenReturn(1);
    when(roomTypeService.findInStore(31L)).thenReturn(roomType(31L, "VIP", "VIP 大包", 20000L, 6000L));
    when(roomTypeService.listByIds(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包", 20000L, 6000L)));

    ResourcePo created = controller.create(new ResourceController.CreateResourceRequest(
        1L, 100L, "KTV_ROOM", "V07", "VIP 07", "  三楼 A 区  ", 20, 31L, null, null, null));

    assertEquals("三楼 A 区", created.getAreaName());
    assertEquals(31L, created.getRoomTypeId());
    assertEquals("VIP", created.getRoomTypeCode());
    assertEquals("VIP 大包", created.getRoomTypeName());
    assertEquals(20000L, created.getRoomTypeUnitPrice());
    assertEquals(6000L, created.getRoomTypeServerUnitPrice());
    ArgumentCaptor<ResourcePo> captor = ArgumentCaptor.forClass(ResourcePo.class);
    verify(resourceMapper).insert(captor.capture());
    assertEquals(31L, captor.getValue().getRoomTypeId());
    assertEquals("三楼 A 区", captor.getValue().getAreaName());
  }

  @Test
  void createDefaultsMainImageToFirstWhenNotSpecified() {
    when(resourceMapper.selectCount(any())).thenReturn(0L);
    when(resourceMapper.insert(any(ResourcePo.class))).thenReturn(1);

    ResourcePo created = controller.create(new ResourceController.CreateResourceRequest(
        1L, 100L, "KTV_ROOM", "V02", "VIP 02", null, 8, null,
        List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), null, null));

    assertEquals("/api/v1/media-public/a.png", created.getMainImageUrl());
    assertNull(created.getDescription());
  }

  @Test
  void createWithoutImagesKeepsEmptyListAndNullMainImage() {
    when(resourceMapper.selectCount(any())).thenReturn(0L);
    when(resourceMapper.insert(any(ResourcePo.class))).thenReturn(1);

    ResourcePo created = controller.create(new ResourceController.CreateResourceRequest(
        1L, 100L, "KTV_ROOM", "V03", "VIP 03", null, 8, null, null, null, null));

    assertEquals(List.of(), created.getImageUrls());
    assertNull(created.getMainImageUrl());
    assertNull(created.getAreaName());
    assertNull(created.getRoomTypeId());
  }

  @Test
  void createRejectsMoreThanNineImagesWithoutInserting() {
    when(resourceMapper.selectCount(any())).thenReturn(0L);
    List<String> urls = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      urls.add("/img/" + i + ".png");
    }

    BusinessException error = assertThrows(BusinessException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V04", "VIP 04", null, 8, null,
            urls, null, null)));

    assertEquals("RESOURCE_INVALID", error.getCode());
    assertEquals("包厢图片最多 9 张，当前 10 张", error.getMessage());
    verify(resourceMapper, never()).insert(any(ResourcePo.class));
  }

  @Test
  void createRejectsMainImageOutsideList() {
    when(resourceMapper.selectCount(any())).thenReturn(0L);

    BusinessException error = assertThrows(BusinessException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V05", "VIP 05", null, 8, null,
            List.of("/a.png"), "/b.png", null)));

    assertEquals("包厢主图必须是已上传图片中的一张", error.getMessage());
    verify(resourceMapper, never()).insert(any(ResourcePo.class));
  }

  @Test
  void createRejectsOverlongDescription() {
    when(resourceMapper.selectCount(any())).thenReturn(0L);

    BusinessException error = assertThrows(BusinessException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V06", "VIP 06", null, 8, null,
            null, null, "包".repeat(256))));

    assertEquals("包厢描述长度不能超过 255 个字符", error.getMessage());
    verify(resourceMapper, never()).insert(any(ResourcePo.class));
  }

  /** 区域列是 varchar(64)：超长必须在落库前 400，而不是把数据库截断/报错暴露给调用方。 */
  @Test
  void createRejectsOverlongAreaName() {
    when(resourceMapper.selectCount(any())).thenReturn(0L);

    ApiException error = assertThrows(ApiException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V08", "VIP 08", "区".repeat(65),
            8, null, null, null, null)));

    assertEquals(400, error.getStatus());
    assertEquals("AREA_NAME_TOO_LONG", error.getCode());
    verify(resourceMapper, never()).insert(any(ResourcePo.class));
  }

  /**
   * 创建时 roomTypeId=0 与编辑清空同一语义：都表示「不指定房型」，落库 null，不再抛 ROOM_TYPE_INVALID。
   * 前端创建按 null、编辑清空按 0 提交，后端必须两端一致。
   */
  @Test
  void createTreatsZeroRoomTypeIdAsUnspecified() {
    when(resourceMapper.selectCount(any())).thenReturn(0L);
    when(resourceMapper.insert(any(ResourcePo.class))).thenReturn(1);

    ResourcePo created = controller.create(new ResourceController.CreateResourceRequest(
        1L, 100L, "KTV_ROOM", "V10", "VIP 10", null, 8, 0L, null, null, null));

    assertNull(created.getRoomTypeId());
    assertNull(created.getRoomTypeName());
    ArgumentCaptor<ResourcePo> captor = ArgumentCaptor.forClass(ResourcePo.class);
    verify(resourceMapper).insert(captor.capture());
    assertNull(captor.getValue().getRoomTypeId(), "0 必须落库为 null（回退门店级单价）");
    verify(roomTypeService, never()).findInStore(any(Long.class));
  }

  /** 房型必须属于本门店：跨门店/不存在的房型 id 直接 400，避免包厢挂到别的门店房型上取错价。 */
  @Test
  void createRejectsRoomTypeOutsideCurrentStore() {
    when(resourceMapper.selectCount(any())).thenReturn(0L);
    when(roomTypeService.findInStore(99L)).thenReturn(null);

    ApiException error = assertThrows(ApiException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V09", "VIP 09", null, 8, 99L,
            null, null, null)));

    assertEquals(400, error.getStatus());
    assertEquals("ROOM_TYPE_INVALID", error.getCode());
    verify(resourceMapper, never()).insert(any(ResourcePo.class));
  }

  @Test
  void createStillRejectsDuplicateResourceCode() {
    when(resourceMapper.selectCount(any())).thenReturn(1L);

    ApiException error = assertThrows(ApiException.class, () -> controller.create(
        new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "V01", "VIP 01", null, 8, null,
            null, null, null)));

    assertEquals(409, error.getStatus());
    assertEquals("RESOURCE_CODE_EXISTS", error.getCode());
    verify(resourceMapper, never()).insert(any(ResourcePo.class));
  }

  @Test
  void updateReplacesImagesDescriptionAndCapacity() {
    ResourcePo existing = existing(7L, "/api/v1/media-public/old.png", "旧描述");
    when(resourceMapper.selectById(7L)).thenReturn(existing);
    when(resourceMapper.update(any(), any())).thenReturn(1);

    ResourcePo updated = controller.update(7L, new ResourceController.UpdateResourceRequest(
        "  新名字  ", "  二楼 B 区  ", 16, null, List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), null,
        "  新描述  "));

    assertEquals("新名字", updated.getName());
    assertEquals("二楼 B 区", updated.getAreaName());
    assertEquals(16, updated.getCapacity());
    assertEquals(List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png"), updated.getImageUrls());
    assertEquals("/api/v1/media-public/a.png", updated.getMainImageUrl());
    assertEquals("新描述", updated.getDescription());
    // 新实现不再把整个 PO 交给 updateById：提交的列必须逐列显式出现在 SET 子句里。
    List<String> writtenColumns = setColumns(capturedUpdate().getSqlSet());
    assertTrue(writtenColumns.containsAll(List.of("name", "area_name", "capacity", "image_urls", "main_image_url",
        "description", "updated_at")), "提交的列必须逐列进 SET，实际: " + writtenColumns);
  }

  /** 房型切换与清空：传新 id 换房型，传 0 清空房型（回到门店级单价）。 */
  @Test
  void updateSwitchesAndClearsRoomType() {
    ResourcePo existing = existing(14L, null, null);
    existing.setRoomTypeId(31L);
    when(resourceMapper.selectById(14L)).thenReturn(existing);
    when(resourceMapper.update(any(), any())).thenReturn(1);
    when(roomTypeService.findInStore(32L)).thenReturn(roomType(32L, "LARGE", "大包", 15000L, null));
    when(roomTypeService.listByIds(any())).thenReturn(List.of(roomType(32L, "LARGE", "大包", 15000L, null)));

    ResourcePo updated = controller.update(14L,
        new ResourceController.UpdateResourceRequest(null, null, null, 32L, null, null, null));

    assertEquals(32L, updated.getRoomTypeId());
    assertEquals("大包", updated.getRoomTypeName());

    ResourcePo cleared = controller.update(14L,
        new ResourceController.UpdateResourceRequest(null, null, null, 0L, null, null, null));

    assertNull(cleared.getRoomTypeId());
    assertNull(cleared.getRoomTypeName(), "清空房型后响应里不能残留上一次的房型名");
    verify(roomTypeService, never()).findInStore(0L);
    verify(resourceMapper, times(2)).update(isNull(), any());
  }

  /**
   * 线上缺陷回归：{@code PUT /admin/resources/{id}} body {@code {"roomTypeId":0}} 本意是解绑房型，
   * 必须真的下发把 room_type_id 置 NULL 的 SET 语句。此前走 {@code updateById(po)}，MyBatis-Plus 默认
   * {@code FieldStrategy.NOT_NULL} 会把 null 字段整列跳过，于是响应看起来已解绑、库里绑定仍在，
   * 后续 DELETE 房型一直 409 {@code ROOM_TYPE_IN_USE}，后台再也删不掉已绑定的房型。
   */
  @Test
  void updateUnbindRoomTypeWritesNullIntoSetClause() {
    ResourcePo existing = existing(30L, null, null);
    existing.setRoomTypeId(31L);
    when(resourceMapper.selectById(30L)).thenReturn(existing);
    when(resourceMapper.update(any(), any())).thenReturn(1);

    controller.update(30L,
        new ResourceController.UpdateResourceRequest(null, null, null, 0L, null, null, null));

    LambdaUpdateWrapper<ResourcePo> wrapper = capturedUpdate();
    String fragment = fragmentOf(wrapper, "room_type_id");
    assertTrue(fragment.matches("room_type_id=#\\{ew\\.paramNameValuePairs\\.MPGENVAL\\d+}"),
        "解绑必须生成显式 SET room_type_id=#{...}，实际 SET: " + wrapper.getSqlSet());
    assertNull(paramOf(wrapper, fragment), "room_type_id 的绑定参数必须是 null，否则库里绑定不会被清掉");
    verify(resourceMapper, never()).updateById(any(ResourcePo.class));
  }

  /** 部分更新语义：未提交的列一个都不能进 SET，否则会把没传的字段一起覆盖成 NULL。 */
  @Test
  void updateWithoutOtherFieldsOnlySetsSubmittedColumns() {
    ResourcePo existing = existing(31L, "/api/v1/media-public/old.png", "旧描述");
    existing.setRoomTypeId(31L);
    existing.setAreaName("三楼 A 区");
    when(resourceMapper.selectById(31L)).thenReturn(existing);
    when(resourceMapper.update(any(), any())).thenReturn(1);

    controller.update(31L,
        new ResourceController.UpdateResourceRequest(null, null, null, 0L, null, null, null));

    String sqlSet = capturedUpdate().getSqlSet();
    assertEquals(List.of("room_type_id", "updated_at"), setColumns(sqlSet),
        "只提交 roomTypeId=0 时只能写 room_type_id 与 updated_at，实际 SET: " + sqlSet);
  }

  /**
   * 同一处的其它「可清空」字段同理：areaName 传空白、图片传空列表、描述传空白，都必须真的写 NULL，
   * 而不是只清响应体。image_urls 是 JSON 列，必须显式带上与实体同款的 JacksonTypeHandler。
   */
  @Test
  void updateClearsAreaMainImageAndDescriptionWithExplicitNulls() {
    ResourcePo existing = existing(32L, "/api/v1/media-public/old.png", "旧描述");
    existing.setAreaName("三楼 A 区");
    when(resourceMapper.selectById(32L)).thenReturn(existing);
    when(resourceMapper.update(any(), any())).thenReturn(1);

    controller.update(32L, new ResourceController.UpdateResourceRequest(
        null, "   ", null, null, List.of(), null, "   "));

    LambdaUpdateWrapper<ResourcePo> wrapper = capturedUpdate();
    assertEquals(List.of("area_name", "image_urls", "main_image_url", "description", "updated_at"),
        setColumns(wrapper.getSqlSet()), "清空区域/图片/描述必须逐一进 SET，实际 SET: " + wrapper.getSqlSet());
    assertNull(paramOf(wrapper, fragmentOf(wrapper, "area_name")));
    assertNull(paramOf(wrapper, fragmentOf(wrapper, "main_image_url")));
    assertNull(paramOf(wrapper, fragmentOf(wrapper, "description")));
    assertEquals(List.of(), paramOf(wrapper, fragmentOf(wrapper, "image_urls")));
    assertTrue(fragmentOf(wrapper, "image_urls").contains("typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler"),
        "JSON 列 image_urls 必须带 JacksonTypeHandler，实际 SET: " + wrapper.getSqlSet());
  }

  @Test
  void updateWithoutAreaFieldKeepsExistingArea() {
    ResourcePo existing = existing(15L, null, null);
    existing.setAreaName("一楼大堂");
    when(resourceMapper.selectById(15L)).thenReturn(existing);
    when(resourceMapper.update(any(), any())).thenReturn(1);

    ResourcePo updated = controller.update(15L,
        new ResourceController.UpdateResourceRequest("改名", null, null, null, null, null, null));

    assertEquals("一楼大堂", updated.getAreaName());
    assertFalse(setColumns(capturedUpdate().getSqlSet()).contains("area_name"),
        "未提交 areaName 时不能把 area_name 写进 SET（原值不能被覆盖成 NULL）");
  }

  @Test
  void updateCanClearImagesAndDescription() {
    ResourcePo existing = existing(8L, "/api/v1/media-public/old.png", "旧描述");
    when(resourceMapper.selectById(8L)).thenReturn(existing);
    when(resourceMapper.update(any(), any())).thenReturn(1);

    ResourcePo updated = controller.update(8L, new ResourceController.UpdateResourceRequest(
        null, "   ", null, null, List.of(), null, "   "));

    assertEquals(List.of(), updated.getImageUrls());
    assertNull(updated.getMainImageUrl());
    assertNull(updated.getDescription());
    assertNull(updated.getAreaName());
  }

  @Test
  void updateWithoutImageFieldKeepsExistingImages() {
    ResourcePo existing = existing(9L, "/api/v1/media-public/old.png", "旧描述");
    when(resourceMapper.selectById(9L)).thenReturn(existing);
    when(resourceMapper.update(any(), any())).thenReturn(1);

    ResourcePo updated = controller.update(9L, new ResourceController.UpdateResourceRequest(
        "改名", null, null, null, null, null, null));

    assertEquals("改名", updated.getName());
    assertEquals(List.of("/api/v1/media-public/old.png"), updated.getImageUrls());
    assertEquals("/api/v1/media-public/old.png", updated.getMainImageUrl());
    assertEquals("旧描述", updated.getDescription());
    List<String> setColumns = setColumns(capturedUpdate().getSqlSet());
    assertFalse(setColumns.contains("image_urls"), "未提交图片时不能写 image_urls，实际 SET 列: " + setColumns);
    assertFalse(setColumns.contains("main_image_url"), "未提交图片时不能写 main_image_url，实际 SET 列: " + setColumns);
  }

  @Test
  void updateRejectsMoreThanNineImages() {
    when(resourceMapper.selectById(10L)).thenReturn(existing(10L, null, null));
    List<String> urls = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      urls.add("/img/" + i + ".png");
    }

    BusinessException error = assertThrows(BusinessException.class, () -> controller.update(
        10L, new ResourceController.UpdateResourceRequest(null, null, null, null, urls, null, null)));

    assertEquals("包厢图片最多 9 张，当前 10 张", error.getMessage());
    verify(resourceMapper, never()).update(any(), any());
  }

  @Test
  void updateRejectsOverlongDescription() {
    when(resourceMapper.selectById(11L)).thenReturn(existing(11L, null, null));

    BusinessException error = assertThrows(BusinessException.class, () -> controller.update(
        11L, new ResourceController.UpdateResourceRequest(null, null, null, null, null, null, "包".repeat(256))));

    assertEquals("包厢描述长度不能超过 255 个字符", error.getMessage());
    verify(resourceMapper, never()).update(any(), any());
  }

  @Test
  void updateRejectsOverlongAreaName() {
    when(resourceMapper.selectById(16L)).thenReturn(existing(16L, null, null));

    ApiException error = assertThrows(ApiException.class, () -> controller.update(
        16L, new ResourceController.UpdateResourceRequest(null, "区".repeat(65), null, null, null, null, null)));

    assertEquals(400, error.getStatus());
    assertEquals("AREA_NAME_TOO_LONG", error.getCode());
    verify(resourceMapper, never()).update(any(), any());
  }

  @Test
  void updateMissingResourceIs404() {
    when(resourceMapper.selectById(404L)).thenReturn(null);

    ApiException error = assertThrows(ApiException.class, () -> controller.update(
        404L, new ResourceController.UpdateResourceRequest("x", null, null, null, null, null, null)));

    assertEquals(404, error.getStatus());
    assertEquals("RESOURCE_NOT_FOUND", error.getCode());
  }

  @Test
  void updateRejectsResourceOfAnotherStore() {
    ResourcePo other = existing(12L, null, null);
    other.setStoreId(999L);
    when(resourceMapper.selectById(12L)).thenReturn(other);

    ApiException error = assertThrows(ApiException.class, () -> controller.update(
        12L, new ResourceController.UpdateResourceRequest("x", null, null, null, null, null, null)));

    assertEquals(403, error.getStatus());
    assertEquals("STORE_SCOPE_DENIED", error.getCode());
    verify(resourceMapper, never()).update(any(), any());
  }

  @Test
  void updateWithoutManagePermissionIs403() {
    TenantContextHolder.set(new TenantContext(1L, null, 100L, 1L, 1, List.of("resource.view")));

    ApiException error = assertThrows(ApiException.class, () -> controller.update(
        13L, new ResourceController.UpdateResourceRequest("x", null, null, null, null, null, null)));

    assertEquals(403, error.getStatus());
    assertEquals("PERMISSION_DENIED", error.getCode());
  }

  /** 列表回填房型：room_type_id 命中房型时返回房型名称，未设置房型的行保持 null。 */
  @Test
  void listReturnsRoomTypeNameForRowsWithRoomType() {
    ResourcePo withType = existing(21L, null, null);
    withType.setRoomTypeId(31L);
    withType.setAreaName("三楼 A 区");
    ResourcePo withoutType = existing(22L, null, null);
    when(resourceMapper.selectList(any())).thenReturn(List.of(withType, withoutType));
    when(roomTypeService.listByIds(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包", 20000L, 6000L)));

    List<ResourcePo> rows = controller.list("KTV_ROOM", 100L, null, null);

    assertEquals("VIP", rows.get(0).getRoomTypeCode());
    assertEquals("VIP 大包", rows.get(0).getRoomTypeName());
    assertEquals("三楼 A 区", rows.get(0).getAreaName());
    assertNull(rows.get(1).getRoomTypeName());
  }

  /** 历史数据无图无描述：查询原样返回 null，不因新字段报错。 */
  @Test
  void listReturnsRowsWithNullMediaForHistoricalData() {
    ResourcePo legacy = existing(20L, null, null);
    when(resourceMapper.selectList(any())).thenReturn(List.of(legacy));

    List<ResourcePo> rows = controller.list("KTV_ROOM", 100L, null, null);

    assertEquals(1, rows.size());
    assertNull(rows.get(0).getImageUrls());
    assertNull(rows.get(0).getMainImageUrl());
    assertNull(rows.get(0).getDescription());
    assertNull(rows.get(0).getAreaName());
    assertNull(rows.get(0).getRoomTypeName());
  }

  /**
   * 列表的**创建时间**闭区间：条件落在 {@code created_at} 列上（不包函数），日期形态两端收口到
   * 当天起点 / 当天末尾（{@code 23:59:59.999}，不溢出到次日），排序保持既有 {@code id ASC}。
   */
  @Test
  void listAppliesClosedCreatedAtRange() {
    when(resourceMapper.selectList(any())).thenReturn(List.of());

    controller.list("KTV_ROOM", 100L, "2026-09-01", "2026-09-30");

    LambdaQueryWrapper<ResourcePo> query = capturedSelect();
    String sql = query.getSqlSegment();
    assertTrue(sql.contains("created_at >="), sql);
    assertTrue(sql.contains("created_at <="), sql);
    assertFalse(sql.contains("DATE("), "时间列不得被函数包裹（索引会失效）: " + sql);
    assertTrue(sql.contains("ORDER BY id ASC"), "排序口径不变: " + sql);
    assertTrue(query.getParamNameValuePairs().values().contains(LocalDateTime.of(2026, 9, 1, 0, 0)), sql);
    assertTrue(query.getParamNameValuePairs().values()
        .contains(LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000)), sql);
  }

  /** 不传时间参数：不拼任何 {@code created_at} 条件（空区间 = 不筛，不是「筛 0 条」）。 */
  @Test
  void listWithoutTimeParamsDoesNotConstrainCreatedAt() {
    when(resourceMapper.selectList(any())).thenReturn(List.of());

    controller.list("KTV_ROOM", 100L, null, null);

    String sql = capturedSelect().getSqlSegment();
    assertFalse(sql.contains("created_at >="), sql);
    assertFalse(sql.contains("created_at <="), sql);
  }

  /** from > to → 400 {@code TIME_RANGE_INVALID}（与全仓其它列表同一错误码），且不查库。 */
  @Test
  void listRejectsInvertedRangeWith400() {
    ApiException failure = assertThrows(ApiException.class,
        () -> controller.list(null, null, "2026-09-30", "2026-09-01"));

    assertEquals(400, failure.getStatus());
    assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
    assertEquals(TimeRangeParams.MESSAGE_TIME_RANGE_INVALID, failure.getMessage());
    verify(resourceMapper, never()).selectList(any());
  }

  @Test
  void listRejectsMalformedTimeWith400() {
    ApiException failure = assertThrows(ApiException.class,
        () -> controller.list(null, null, "2026/09/01", null));

    assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
    verify(resourceMapper, never()).selectList(any());
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<ResourcePo> capturedSelect() {
    ArgumentCaptor<LambdaQueryWrapper<ResourcePo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(resourceMapper).selectList(captor.capture());
    return captor.getValue();
  }

  /**
   * 捕获本次更新下发的显式 SET 包装器。第一个参数固定断言为 null：所有要写的列都必须由 wrapper 显式列出，
   * 一旦有人改回 {@code updateById(po)}，这里就会失败（那正是把 null 列静默跳过的旧实现）。
   */
  private LambdaUpdateWrapper<ResourcePo> capturedUpdate() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<LambdaUpdateWrapper<ResourcePo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(resourceMapper).update(isNull(), captor.capture());
    return captor.getValue();
  }

  /** SET 子句里的列名，按出现顺序。 */
  private static List<String> setColumns(String sqlSet) {
    List<String> columns = new ArrayList<>();
    Matcher matcher = SET_FRAGMENT.matcher(sqlSet == null ? "" : sqlSet);
    while (matcher.find()) {
      columns.add(matcher.group(1));
    }
    return columns;
  }

  /** 取某个列的完整 SET 片段（形如 {@code room_type_id=#{ew.paramNameValuePairs.MPGENVAL7}}）。 */
  private static String fragmentOf(LambdaUpdateWrapper<ResourcePo> wrapper, String column) {
    Matcher matcher = SET_FRAGMENT.matcher(wrapper.getSqlSet() == null ? "" : wrapper.getSqlSet());
    while (matcher.find()) {
      if (column.equals(matcher.group(1))) {
        return matcher.group();
      }
    }
    throw new AssertionError("SET 子句里没有列 " + column + "，实际: " + wrapper.getSqlSet());
  }

  /** 取某个 SET 片段绑定的参数值；参数名不在绑定表里（等于该列根本没进 SQL）时直接失败。 */
  private static Object paramOf(LambdaUpdateWrapper<ResourcePo> wrapper, String fragment) {
    Matcher matcher = SET_FRAGMENT.matcher(fragment);
    assertTrue(matcher.matches(), "无法解析 SET 片段: " + fragment);
    String paramName = matcher.group(2);
    Map<String, Object> params = wrapper.getParamNameValuePairs();
    assertTrue(params.containsKey(paramName), "SET 片段缺少绑定参数 " + paramName + ": " + fragment);
    return params.get(paramName);
  }

  private static RoomTypePo roomType(Long id, String code, String name, Long unitPrice, Long serverUnitPrice) {
    RoomTypePo po = new RoomTypePo();
    po.setId(id);
    po.setTenantId(1L);
    po.setStoreId(100L);
    po.setCode(code);
    po.setName(name);
    po.setUnitPrice(unitPrice);
    po.setServerUnitPrice(serverUnitPrice);
    po.setStatus("ACTIVE");
    return po;
  }

  private static ResourcePo existing(Long id, String imageUrl, String description) {
    ResourcePo po = new ResourcePo();
    po.setId(id);
    po.setTenantId(1L);
    po.setStoreId(100L);
    po.setResourceType("KTV_ROOM");
    po.setResourceCode("V" + id);
    po.setName("包厢 " + id);
    po.setCapacity(8);
    po.setStatus("ENABLED");
    po.setImageUrls(imageUrl == null ? null : List.of(imageUrl));
    po.setMainImageUrl(imageUrl);
    po.setDescription(description);
    return po;
  }
}
