package com.gvchat.platform.resource.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.resource.infra.persistence.mapper.ResourceMapper;
import com.gvchat.platform.resource.infra.persistence.mapper.RoomTypeMapper;
import com.gvchat.platform.resource.infra.persistence.po.RoomTypePo;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 房型字典 CRUD：编码统一大写、重名/重码 409、单价非法 400、被包厢引用不可删、跨门店不可见。
 * 资源服务没有 H2 测试 schema（集成测试走真库），故本用例用 Mockito 校验落库字段与错误码。
 */
class RoomTypeApplicationServiceTest {

  private RoomTypeMapper roomTypeMapper;
  private ResourceMapper resourceMapper;
  private AuditClient auditClient;
  private RoomTypeApplicationService service;

  @BeforeEach
  void setUp() {
    // MyBatis-Plus 的列名解析与参数绑定是懒求值的：纯单元测试（无 Spring/MyBatis 上下文）里，
    // 必须先注册实体的 TableInfo，wrapper.getSqlSegment() 才能渲染出 SQL 片段并填充绑定参数表。
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RoomTypePo.class);
    roomTypeMapper = mock(RoomTypeMapper.class);
    resourceMapper = mock(ResourceMapper.class);
    auditClient = mock(AuditClient.class);
    service = new RoomTypeApplicationService(roomTypeMapper, resourceMapper, auditClient);
    TenantContextHolder.set(new TenantContext(1L, null, 100L, 1L, 1, List.of("resource.manage")));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void createNormalizesCodeToUpperCaseAndStoresPrices() {
    when(roomTypeMapper.selectCount(any())).thenReturn(0L);
    when(roomTypeMapper.insert(any(RoomTypePo.class))).thenReturn(1);

    RoomTypePo created = service.create(new RoomTypeApplicationService.RoomTypeCommand(
        "  vip  ", "  VIP 大包  ", 20, 20000L, 6000L, 3, null));

    assertEquals("VIP", created.getCode());
    assertEquals("VIP 大包", created.getName());
    assertEquals(1L, created.getTenantId());
    assertEquals(100L, created.getStoreId());
    assertEquals(20, created.getCapacity());
    assertEquals(20000L, created.getUnitPrice());
    assertEquals(6000L, created.getServerUnitPrice());
    assertEquals(3, created.getSortOrder());
    assertEquals("ACTIVE", created.getStatus());
    verify(roomTypeMapper).insert(created);
  }

  /** 单价传 0 = 该房型不定价（回退门店级单价），落库为 null，避免「0 元房型」把账单算成 0。 */
  @Test
  void createTreatsZeroPriceAsFallbackToStoreLevel() {
    when(roomTypeMapper.selectCount(any())).thenReturn(0L);
    when(roomTypeMapper.insert(any(RoomTypePo.class))).thenReturn(1);

    RoomTypePo created = service.create(new RoomTypeApplicationService.RoomTypeCommand(
        "SMALL", "小包", 6, 0L, 0L, 0, "ACTIVE"));

    assertNull(created.getUnitPrice());
    assertNull(created.getServerUnitPrice());
  }

  @Test
  void createRejectsDuplicateCodeWith409() {
    // 第一次查编码（selectCount）命中，说明门店内已有同编码房型
    when(roomTypeMapper.selectCount(any())).thenReturn(1L);

    ApiException error = assertThrows(ApiException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", 20, 20000L, null, 0, null)));

    assertEquals(409, error.getStatus());
    assertEquals("ROOM_TYPE_CODE_EXISTS", error.getCode());
    verify(roomTypeMapper, never()).insert(any(RoomTypePo.class));
  }

  @Test
  void createRejectsDuplicateNameWith409() {
    // 编码查询不命中（0），名称查询命中（1）：报「名称已存在」而不是「编码已存在」
    when(roomTypeMapper.selectCount(any())).thenReturn(0L, 1L);

    ApiException error = assertThrows(ApiException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP2", "VIP 大包", 20, 20000L, null, 0, null)));

    assertEquals(409, error.getStatus());
    assertEquals("ROOM_TYPE_NAME_EXISTS", error.getCode());
    verify(roomTypeMapper, never()).insert(any(RoomTypePo.class));
  }

  @Test
  void createRejectsBlankNameAndInvalidInput() {
    BusinessException blankName = assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "   ", null, null, null, null, null)));
    assertEquals("ROOM_TYPE_INVALID", blankName.getCode());

    BusinessException negativePrice = assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", null, -1L, null, null, null)));
    assertEquals("ROOM_TYPE_INVALID", negativePrice.getCode());

    BusinessException zeroCapacity = assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", 0, null, null, null, null)));
    assertEquals("ROOM_TYPE_INVALID", zeroCapacity.getCode());

    BusinessException badStatus = assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", null, null, null, null, "DELETED")));
    assertEquals("ROOM_TYPE_INVALID", badStatus.getCode());
    verify(roomTypeMapper, never()).insert(any(RoomTypePo.class));
  }

  @Test
  void updateRenamesAndChecksDuplicatesExcludingSelf() {
    RoomTypePo existing = roomType(31L, "VIP", "VIP 大包");
    when(roomTypeMapper.selectById(31L)).thenReturn(existing);
    when(roomTypeMapper.selectCount(any())).thenReturn(0L);

    RoomTypePo updated = service.update(31L, new RoomTypeApplicationService.RoomTypeCommand(
        null, "  豪华大包  ", 24, 30000L, null, 5, "DISABLED"));

    assertEquals("豪华大包", updated.getName());
    assertEquals("VIP", updated.getCode());
    assertEquals(24, updated.getCapacity());
    assertEquals(30000L, updated.getUnitPrice());
    assertEquals(5, updated.getSortOrder());
    assertEquals("DISABLED", updated.getStatus());
    LambdaUpdateWrapper<RoomTypePo> wrapper = capturedUpdate();
    assertTrue(wrapper.getSqlSet().contains("name"), "本次提交的列必须在 SET 里");
    assertTrue(wrapper.getParamNameValuePairs().values().contains("豪华大包"));
  }

  /**
   * 回归：显式提交 0（= 取消该房型定价、回退门店级单价）必须真的把 {@code unit_price} 写成 NULL。
   *
   * <p>原实现是 {@code po.setUnitPrice(null) + updateById(po)}，MyBatis-Plus 默认 FieldStrategy.NOT_NULL
   * 会把 null 字段整列跳过，于是接口响应看着已清空、库里单价还在，该房型永远按旧价计费，后台也无法取消定价。
   */
  @Test
  void updateClearsRoomTypeUnitPriceWhenExplicitlySubmittedZero() {
    RoomTypePo existing = roomType(31L, "VIP", "VIP 大包");
    existing.setUnitPrice(20000L);
    existing.setServerUnitPrice(6000L);
    when(roomTypeMapper.selectById(31L)).thenReturn(existing);
    when(roomTypeMapper.selectCount(any())).thenReturn(0L);

    RoomTypePo updated = service.update(31L, new RoomTypeApplicationService.RoomTypeCommand(
        null, null, null, 0L, null, null, null));

    assertNull(updated.getUnitPrice(), "0 = 取消房型定价，响应即为 null");
    assertEquals(6000L, updated.getServerUnitPrice(), "未提交的服务人员单价必须原样保留");

    LambdaUpdateWrapper<RoomTypePo> wrapper = capturedUpdate();
    String sqlSet = wrapper.getSqlSet();
    assertTrue(sqlSet.contains("unit_price"), "清空必须让 unit_price 进 SET 子句");
    assertTrue(sqlSet.contains("server_unit_price"), "未提交的列也必须写回原值，避免被置空");
    assertTrue(wrapper.getParamNameValuePairs().values().contains(null),
        "清除定价必须把 NULL 作为绑定参数下发（updateById 做不到这一点）");
  }

  @Test
  void updateMissingRoomTypeIs404() {
    when(roomTypeMapper.selectById(404L)).thenReturn(null);

    ApiException error = assertThrows(ApiException.class, () -> service.update(404L,
        new RoomTypeApplicationService.RoomTypeCommand(null, "新名", null, null, null, null, null)));

    assertEquals(404, error.getStatus());
    assertEquals("ROOM_TYPE_NOT_FOUND", error.getCode());
  }

  /**
   * 房型图片（V8）：C 端「选择包厢类型」按房型展示，房型必须能自带样板图。
   * 规则与包厢完全一致：去空去重、主图必须来自列表、未指定主图时取第一张。
   */
  @Test
  void createStoresImagesAndDefaultsMainImageToFirst() {
    when(roomTypeMapper.selectCount(any())).thenReturn(0L);
    when(roomTypeMapper.insert(any(RoomTypePo.class))).thenReturn(1);

    RoomTypePo created = service.create(new RoomTypeApplicationService.RoomTypeCommand(
        "SMALL", "小包", 6, null, null, 0, "ACTIVE",
        List.of(" /a.png ", "/a.png", "", "/b.png"), null));

    assertEquals(List.of("/a.png", "/b.png"), created.getImageUrls(), "去空去重去首尾空白");
    assertEquals("/a.png", created.getMainImageUrl(), "未指定主图时取第一张");
    verify(roomTypeMapper).insert(created);
  }

  @Test
  void createWithoutImagesStoresEmptyListAndNullMainImage() {
    when(roomTypeMapper.selectCount(any())).thenReturn(0L);
    when(roomTypeMapper.insert(any(RoomTypePo.class))).thenReturn(1);

    RoomTypePo created = service.create(new RoomTypeApplicationService.RoomTypeCommand(
        "SMALL", "小包", 6, null, null, 0, "ACTIVE"));

    assertEquals(List.of(), created.getImageUrls());
    assertNull(created.getMainImageUrl());
  }

  @Test
  void createRejectsMoreThanNineImages() {
    when(roomTypeMapper.selectCount(any())).thenReturn(0L);
    List<String> tooMany = List.of("/1.png", "/2.png", "/3.png", "/4.png", "/5.png",
        "/6.png", "/7.png", "/8.png", "/9.png", "/10.png");

    BusinessException error = assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("SMALL", "小包", 6, null, null, 0, "ACTIVE", tooMany, null)));

    assertEquals(ResourceMedia.ERROR_CODE, error.getCode());
    assertTrue(error.getMessage().contains("房型图片最多 9 张"), error.getMessage());
    verify(roomTypeMapper, never()).insert(any(RoomTypePo.class));
  }

  @Test
  void createRejectsMainImageOutsideImageList() {
    when(roomTypeMapper.selectCount(any())).thenReturn(0L);

    BusinessException error = assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("SMALL", "小包", 6, null, null, 0, "ACTIVE",
            List.of("/a.png", "/b.png"), "/c.png")));

    assertEquals(ResourceMedia.ERROR_CODE, error.getCode());
    assertTrue(error.getMessage().contains("房型主图必须是已上传图片中的一张"), error.getMessage());
  }

  /** 编辑提交空数组 = 清空图片：image_urls 与 main_image_url 都必须进 SET（否则只清响应不清库）。 */
  @Test
  void updateClearsImagesWhenEmptyListSubmitted() {
    RoomTypePo existing = roomType(31L, "VIP", "VIP 大包");
    existing.setImageUrls(List.of("/a.png", "/b.png"));
    existing.setMainImageUrl("/a.png");
    when(roomTypeMapper.selectById(31L)).thenReturn(existing);
    when(roomTypeMapper.selectCount(any())).thenReturn(0L);

    RoomTypePo updated = service.update(31L, new RoomTypeApplicationService.RoomTypeCommand(
        null, null, null, null, null, null, null, List.of(), null));

    assertEquals(List.of(), updated.getImageUrls());
    assertNull(updated.getMainImageUrl());
    LambdaUpdateWrapper<RoomTypePo> wrapper = capturedUpdate();
    assertTrue(wrapper.getSqlSet().contains("image_urls"), "清空图片必须让 image_urls 进 SET 子句");
    assertTrue(wrapper.getSqlSet().contains("main_image_url"), "主图必须一并清空");
  }

  /** 编辑不提交图片字段 = 本次不动图片：已保存的图片必须原样保留（并原样写回）。 */
  @Test
  void updateKeepsExistingImagesWhenFieldAbsent() {
    RoomTypePo existing = roomType(31L, "VIP", "VIP 大包");
    existing.setImageUrls(List.of("/a.png"));
    existing.setMainImageUrl("/a.png");
    when(roomTypeMapper.selectById(31L)).thenReturn(existing);
    when(roomTypeMapper.selectCount(any())).thenReturn(0L);

    RoomTypePo updated = service.update(31L, new RoomTypeApplicationService.RoomTypeCommand(
        null, "VIP 大包", 20, null, null, null, null));

    assertEquals(List.of("/a.png"), updated.getImageUrls());
    assertEquals("/a.png", updated.getMainImageUrl());
  }

  /** 编辑换图：主图必须落在新列表里，否则 400（避免主图指向已删除的图）。 */
  @Test
  void updateRejectsMainImageOutsideSubmittedList() {
    RoomTypePo existing = roomType(31L, "VIP", "VIP 大包");
    when(roomTypeMapper.selectById(31L)).thenReturn(existing);
    when(roomTypeMapper.selectCount(any())).thenReturn(0L);

    BusinessException error = assertThrows(BusinessException.class, () -> service.update(31L,
        new RoomTypeApplicationService.RoomTypeCommand(null, null, null, null, null, null, null,
            List.of("/new.png"), "/old.png")));

    assertEquals(ResourceMedia.ERROR_CODE, error.getCode());
    verify(roomTypeMapper, never()).update(isNull(), any());
  }

  @Test
  void deleteRejectsRoomTypeStillReferencedByRooms() {
    when(roomTypeMapper.selectById(31L)).thenReturn(roomType(31L, "VIP", "VIP 大包"));
    when(resourceMapper.selectCount(any())).thenReturn(2L);

    ApiException error = assertThrows(ApiException.class, () -> service.delete(31L));

    assertEquals(409, error.getStatus());
    assertEquals("ROOM_TYPE_IN_USE", error.getCode());
    verify(roomTypeMapper, never()).deleteById(any(Long.class));
  }

  @Test
  void deleteRemovesUnreferencedRoomType() {
    when(roomTypeMapper.selectById(31L)).thenReturn(roomType(31L, "VIP", "VIP 大包"));
    when(resourceMapper.selectCount(any())).thenReturn(0L);
    when(roomTypeMapper.deleteById(31L)).thenReturn(1);

    service.delete(31L);

    verify(roomTypeMapper).deleteById(31L);
  }

  @Test
  void findInStoreReturnsNullForUnknownId() {
    when(roomTypeMapper.selectOne(any())).thenReturn(null);

    assertNull(service.findInStore(99L));
  }

  @Test
  void listRejectsAnotherStoreWith403() {
    ApiException error = assertThrows(ApiException.class, () -> service.list(999L, null));

    assertEquals(403, error.getStatus());
    assertEquals("STORE_SCOPE_DENIED", error.getCode());
  }

  @Test
  void listUsesContextStoreWhenStoreIdAbsent() {
    when(roomTypeMapper.selectList(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    List<RoomTypePo> rows = service.list(null, "active");

    assertEquals(1, rows.size());
    verify(roomTypeMapper).selectList(any());
  }

  /**
   * 线上缺陷回归：前端「房型管理」列表不带 status 调用，此前 {@code eq(cond, ..., status.trim())} 的实参
   * 会先求值，status 为 null 直接 NPE 被兜成 500。现在必须 200 返回全部（查询里不附加状态条件）。
   */
  @Test
  void listWithoutStatusReturnsAllRowsWithoutFilter() {
    when(roomTypeMapper.selectList(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    List<RoomTypePo> rows = assertDoesNotThrow(() -> service.list(null, null));

    assertEquals(1, rows.size());
    LambdaQueryWrapper<RoomTypePo> query = capturedQuery(roomTypeMapper);
    String sql = query.getSqlSegment();
    assertFalse(sql.contains("status"), "不带 status 时不应生成状态过滤条件: " + sql);
    assertEquals(2, query.getParamNameValuePairs().size(),
        "不带 status 时查询条件只能有 tenantId/storeId 两个绑定参数");
  }

  /** status 为空串/纯空白同样按「不过滤」处理（部分客户端会拼出 status=）。 */
  @Test
  void listWithBlankStatusReturnsAllRowsWithoutFilter() {
    when(roomTypeMapper.selectList(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    assertEquals(1, assertDoesNotThrow(() -> service.list(null, "   ")).size());
    LambdaQueryWrapper<RoomTypePo> query = capturedQuery(roomTypeMapper);
    assertFalse(query.getSqlSegment().contains("status"));
    assertEquals(2, query.getParamNameValuePairs().size());
  }

  /** 显式传本门店 storeId 且 status 为空串：只按门店过滤，仍然「返回全部房型」。 */
  @Test
  void listWithExplicitStoreAndBlankStatusReturnsAllRows() {
    when(roomTypeMapper.selectList(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    assertEquals(1, assertDoesNotThrow(() -> service.list(100L, "")).size());
    LambdaQueryWrapper<RoomTypePo> query = capturedQuery(roomTypeMapper);
    assertFalse(query.getSqlSegment().contains("status"));
    assertEquals(2, query.getParamNameValuePairs().size());
  }

  @Test
  void listFiltersByActiveStatusWithCaseAndBlankTolerance() {
    when(roomTypeMapper.selectList(any())).thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    List<RoomTypePo> rows = service.list(null, " active ");

    assertEquals(1, rows.size());
    LambdaQueryWrapper<RoomTypePo> query = capturedQuery(roomTypeMapper);
    assertTrue(query.getSqlSegment().contains("status"), "status=active 必须生成状态过滤条件");
    assertEquals(3, query.getParamNameValuePairs().size());
    assertTrue(query.getParamNameValuePairs().containsValue("ACTIVE"),
        "status=active 应归一为大写 ACTIVE 再作为筛选值");
  }

  @Test
  void listFiltersByDisabledStatus() {
    when(roomTypeMapper.selectList(any())).thenReturn(List.of());

    assertEquals(0, service.list(null, "DISABLED").size());
    LambdaQueryWrapper<RoomTypePo> query = capturedQuery(roomTypeMapper);
    assertTrue(query.getSqlSegment().contains("status"));
    assertTrue(query.getParamNameValuePairs().containsValue("DISABLED"));
  }

  /** 非法状态值必须是可读的 400（ROOM_TYPE_STATUS_INVALID），而不是 500 或静默返回空列表。 */
  @Test
  void listRejectsInvalidStatusWith400() {
    ApiException error = assertThrows(ApiException.class, () -> service.list(null, "DELETED"));

    assertEquals(400, error.getStatus());
    assertEquals("ROOM_TYPE_STATUS_INVALID", error.getCode());
    assertEquals("房型状态筛选值只能是 ACTIVE 或 DISABLED", error.getMessage());
    verify(roomTypeMapper, never()).selectList(any());
  }

  @Test
  void normalizeStatusFilterTreatsNullAndBlankAsNoFilter() {
    assertNull(RoomTypeApplicationService.normalizeStatusFilter(null));
    assertNull(RoomTypeApplicationService.normalizeStatusFilter(""));
    assertNull(RoomTypeApplicationService.normalizeStatusFilter(" \t "));
    assertEquals("ACTIVE", RoomTypeApplicationService.normalizeStatusFilter(" active "));
    assertEquals("DISABLED", RoomTypeApplicationService.normalizeStatusFilter("disabled"));
  }

  @Test
  void listWithoutStoreContextIsRejected() {
    TenantContextHolder.clear();

    BusinessException error = assertThrows(BusinessException.class, () -> service.list(null, null));

    assertEquals("SAAS_CONTEXT_REQUIRED", error.getCode());
  }

  /**
   * 取回实际传给 mapper 的查询条件。status 为 null/空白时 {@code .eq(false, ...)} 不追加条件，
   * 故参数表里只有 tenantId/storeId 两项——据此断言「null/空串 = 不过滤（返回全部）」。
   */
  @SuppressWarnings("unchecked")
  private static LambdaQueryWrapper<RoomTypePo> capturedQuery(RoomTypeMapper mapper) {
    ArgumentCaptor<LambdaQueryWrapper<RoomTypePo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue();
  }

  /** 取回实际下发的 UPDATE 包装器：断言本次要写的列与绑定参数（尤其是清除定价下发的 NULL）。 */
  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<RoomTypePo> capturedUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<RoomTypePo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(roomTypeMapper).update(isNull(), captor.capture());
    return captor.getValue();
  }

  /** ③ 失败留痕：房型新建失败（编码重复）除 409 外必须落一条 FAILURE，带 errorCode 与门店范围。 */
  @Test
  void createFailureIsAuditedWithErrorCode() {
    when(roomTypeMapper.selectCount(any())).thenReturn(1L);

    assertThrows(ApiException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", 20, 20000L, null, 0, null)));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("resource.roomtype.create", record.action());
    assertEquals("res_room_type", record.resourceType());
    assertEquals("VIP", record.resourceId());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ROOM_TYPE_CODE_EXISTS", record.errorCode());
    assertEquals(100L, record.storeId());
  }

  /** ③ 失败留痕：房型编辑失败（不存在）同样必须留痕，错误码用既有 ApiException 稳定码。 */
  @Test
  void updateFailureIsAuditedWithErrorCode() {
    when(roomTypeMapper.selectById(404L)).thenReturn(null);

    assertThrows(ApiException.class, () -> service.update(404L,
        new RoomTypeApplicationService.RoomTypeCommand(null, "新名", null, null, null, null, null)));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("resource.roomtype.update", record.action());
    assertEquals("404", record.resourceId());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ROOM_TYPE_NOT_FOUND", record.errorCode());
  }

  /** 领域异常（BusinessException）也必须带出稳定码，而不是退化成异常类名。 */
  @Test
  void createFailureCarriesBusinessExceptionCode() {
    assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", null, -1L, null, null, null)));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ROOM_TYPE_INVALID", record.errorCode());
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }

  private static RoomTypePo roomType(Long id, String code, String name) {    RoomTypePo po = new RoomTypePo();
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
