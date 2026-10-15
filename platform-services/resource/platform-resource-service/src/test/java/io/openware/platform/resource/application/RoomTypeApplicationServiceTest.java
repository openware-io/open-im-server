package io.openware.platform.resource.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
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
 * 房型字典 CRUD：编码统一大写、重名/重码 409、单价非法 400、被包厢引用不可删、跨门店不可见。
 *
 * <p><b>分层后的断言归属</b>：此前本文件断言 {@code LambdaQueryWrapper} 的条件片段与绑定参数个数
 * （用来推断「status 为空时不加状态过滤」）。查询条件现在由适配器生成，那类断言属于适配器测试；
 * 本文件只断言**应用服务职责**：归一后的筛选值是什么、调用了哪个端口方法、返回什么错误码。
 *
 * <p>同理，「清空单价是否真的把 NULL 写进 SQL」由 {@code RoomTypeRepositoryAdapterTest} 验证；
 * 这里锁定传给端口的模型确实带 {@code unitPrice == null}。
 */
class RoomTypeApplicationServiceTest {

  private RoomTypeRepository roomTypeRepository;
  private ResourceRepository resourceRepository;
  private AuditClient auditClient;
  private RoomTypeApplicationService service;

  @BeforeEach
  void setUp() {
    roomTypeRepository = mock(RoomTypeRepository.class);
    resourceRepository = mock(ResourceRepository.class);
    auditClient = mock(AuditClient.class);
    service = new RoomTypeApplicationService(roomTypeRepository, resourceRepository, auditClient);
    when(roomTypeRepository.listInStore(any(), any(), any())).thenReturn(List.of());
    TenantContextHolder.set(new TenantContext(1L, null, 100L, 1L, 1, List.of("resource.manage")));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /** 端口新增返回落库后的聚合（真实适配器回填主键，测试替身等价模拟）。 */
  private void insertReturnsArgument() {
    when(roomTypeRepository.insert(any(RoomType.class))).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void createNormalizesCodeToUpperCaseAndStoresPrices() {
    insertReturnsArgument();

    RoomType created = service.create(new RoomTypeApplicationService.RoomTypeCommand(
        "  vip  ", "  VIP 大包  ", 20, 20000L, 6000L, 3, null));

    assertEquals("VIP", created.code());
    assertEquals("VIP 大包", created.name());
    assertEquals(1L, created.tenantId());
    assertEquals(100L, created.storeId());
    assertEquals(20, created.capacity());
    assertEquals(20000L, created.unitPrice());
    assertEquals(6000L, created.serverUnitPrice());
    assertEquals(3, created.sortOrder());
    assertEquals(RoomType.STATUS_ACTIVE, created.status());
    verify(roomTypeRepository).insert(created);
  }

  /** 单价传 0 = 该房型不定价（回退门店级单价），落库为 null，避免「0 元房型」把账单算成 0。 */
  @Test
  void createTreatsZeroPriceAsFallbackToStoreLevel() {
    insertReturnsArgument();

    RoomType created = service.create(new RoomTypeApplicationService.RoomTypeCommand(
        "SMALL", "小包", 6, 0L, 0L, 0, "ACTIVE"));

    assertNull(created.unitPrice());
    assertNull(created.serverUnitPrice());
  }

  @Test
  void createRejectsDuplicateCodeWith409() {
    when(roomTypeRepository.countByCode(any(), any(), any(), any())).thenReturn(1L);

    ApiException error = assertThrows(ApiException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", 20, 20000L, null, 0, null)));

    assertEquals(409, error.getStatus());
    assertEquals("ROOM_TYPE_CODE_EXISTS", error.getCode());
    verify(roomTypeRepository, never()).insert(any(RoomType.class));
  }

  @Test
  void createRejectsDuplicateNameWith409() {
    // 编码查询不命中，名称查询命中：报「名称已存在」而不是「编码已存在」
    when(roomTypeRepository.countByCode(any(), any(), any(), any())).thenReturn(0L);
    when(roomTypeRepository.countByName(any(), any(), any(), any())).thenReturn(1L);

    ApiException error = assertThrows(ApiException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP2", "VIP 大包", 20, 20000L, null, 0, null)));

    assertEquals(409, error.getStatus());
    assertEquals("ROOM_TYPE_NAME_EXISTS", error.getCode());
    verify(roomTypeRepository, never()).insert(any(RoomType.class));
  }

  @Test
  void createRejectsBlankNameAndInvalidInput() {
    assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "   ", null, null, null, null, null)));
    assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", null, -1L, null, null, null)));
    assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", 0, null, null, null, null)));
    assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", null, null, null, null, "DELETED")));

    verify(roomTypeRepository, never()).insert(any(RoomType.class));
  }

  @Test
  void updateRenamesAndChecksDuplicatesExcludingSelf() {
    when(roomTypeRepository.findById(31L)).thenReturn(Optional.of(roomType(31L, "VIP", "VIP 大包")));
    when(roomTypeRepository.countByCode(any(), any(), any(), any())).thenReturn(0L);
    when(roomTypeRepository.countByName(any(), any(), any(), any())).thenReturn(0L);
    when(roomTypeRepository.updateSubmitted(any(RoomType.class))).thenReturn(true);

    RoomType updated = service.update(31L, new RoomTypeApplicationService.RoomTypeCommand(
        null, "  豪华大包  ", 24, 30000L, null, 5, "DISABLED"));

    assertEquals("豪华大包", updated.name());
    assertEquals("VIP", updated.code());
    assertEquals(24, updated.capacity());
    assertEquals(30000L, updated.unitPrice());
    assertEquals(5, updated.sortOrder());
    assertEquals("DISABLED", updated.status());
    ArgumentCaptor<RoomType> captor = ArgumentCaptor.forClass(RoomType.class);
    verify(roomTypeRepository).updateSubmitted(captor.capture());
    assertEquals("豪华大包", captor.getValue().name());
    // 唯一性判据必须排除自身行
    verify(roomTypeRepository).countByCode(1L, 100L, "VIP", 31L);
    verify(roomTypeRepository).countByName(1L, 100L, "豪华大包", 31L);
  }

  /**
   * 回归：显式提交 0（= 取消该房型定价、回退门店级单价）必须真的把 {@code unit_price} 写成 NULL。
   *
   * <p>原实现是 {@code po.setUnitPrice(null) + updateById(po)}，MyBatis-Plus 默认 FieldStrategy.NOT_NULL
   * 会把 null 字段整列跳过，于是接口响应看着已清空、库里单价还在，该房型永远按旧价计费。「null 是否真的
   * 进 SET」由适配器测试验证；这里锁定传给端口的模型确实带 {@code unitPrice == null}，且未提交的
   * 服务人员单价原样保留。
   */
  @Test
  void updateClearsRoomTypeUnitPriceWhenExplicitlySubmittedZero() {
    when(roomTypeRepository.findById(31L))
        .thenReturn(Optional.of(roomTypeWithPrices(31L, 20000L, 6000L)));
    when(roomTypeRepository.countByCode(any(), any(), any(), any())).thenReturn(0L);
    when(roomTypeRepository.countByName(any(), any(), any(), any())).thenReturn(0L);
    when(roomTypeRepository.updateSubmitted(any(RoomType.class))).thenReturn(true);

    RoomType updated = service.update(31L, new RoomTypeApplicationService.RoomTypeCommand(
        null, null, null, 0L, null, null, null));

    assertNull(updated.unitPrice(), "0 = 取消房型定价，响应即为 null");
    assertEquals(6000L, updated.serverUnitPrice(), "未提交的服务人员单价必须原样保留");

    ArgumentCaptor<RoomType> captor = ArgumentCaptor.forClass(RoomType.class);
    verify(roomTypeRepository).updateSubmitted(captor.capture());
    assertNull(captor.getValue().unitPrice(), "清空单价必须把 null 交给端口（适配器负责真的写 NULL）");
    assertEquals(6000L, captor.getValue().serverUnitPrice(), "未提交的列必须写回原值，避免被置空");
  }

  @Test
  void updateMissingRoomTypeIs404() {
    when(roomTypeRepository.findById(404L)).thenReturn(Optional.empty());

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
    insertReturnsArgument();

    RoomType created = service.create(new RoomTypeApplicationService.RoomTypeCommand(
        "SMALL", "小包", 6, null, null, 0, "ACTIVE",
        List.of(" /a.png ", "/a.png", "", "/b.png"), null));

    assertEquals(List.of("/a.png", "/b.png"), created.imageUrls(), "去空去重去首尾空白");
    assertEquals("/a.png", created.mainImageUrl(), "未指定主图时取第一张");
    verify(roomTypeRepository).insert(created);
  }

  @Test
  void createWithoutImagesStoresEmptyListAndNullMainImage() {
    insertReturnsArgument();

    RoomType created = service.create(new RoomTypeApplicationService.RoomTypeCommand(
        "SMALL", "小包", 6, null, null, 0, "ACTIVE", null, null));

    assertEquals(List.of(), created.imageUrls());
    assertNull(created.mainImageUrl());
  }

  @Test
  void createRejectsMoreThanNineImages() {
    List<String> tooMany = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tooMany.add("/img/" + i + ".png");
    }

    assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("SMALL", "小包", 6, null, null, 0, "ACTIVE", tooMany, null)));

    verify(roomTypeRepository, never()).insert(any(RoomType.class));
  }

  @Test
  void createRejectsMainImageOutsideImageList() {
    assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("SMALL", "小包", 6, null, null, 0, "ACTIVE",
            List.of("/a.png"), "/b.png")));

    verify(roomTypeRepository, never()).insert(any(RoomType.class));
  }

  /** 编辑提交空数组 = 清空图片：端口收到的模型必须是空列表 + null 主图（空值语义交给端口写库）。 */
  @Test
  void updateClearsImagesWhenEmptyListSubmitted() {
    when(roomTypeRepository.findById(31L))
        .thenReturn(Optional.of(roomTypeWithImages(31L, List.of("/a.png", "/b.png"), "/a.png")));
    when(roomTypeRepository.countByCode(any(), any(), any(), any())).thenReturn(0L);
    when(roomTypeRepository.countByName(any(), any(), any(), any())).thenReturn(0L);
    when(roomTypeRepository.updateSubmitted(any(RoomType.class))).thenReturn(true);

    RoomType updated = service.update(31L, new RoomTypeApplicationService.RoomTypeCommand(
        null, null, null, null, null, null, null, List.of(), null));

    assertEquals(List.of(), updated.imageUrls());
    assertNull(updated.mainImageUrl());
    ArgumentCaptor<RoomType> captor = ArgumentCaptor.forClass(RoomType.class);
    verify(roomTypeRepository).updateSubmitted(captor.capture());
    assertEquals(List.of(), captor.getValue().imageUrls());
    assertNull(captor.getValue().mainImageUrl());
  }

  /** 编辑不提交图片字段 = 本次不动图片：已保存的图片必须原样保留（并原样写回）。 */
  @Test
  void updateKeepsExistingImagesWhenFieldAbsent() {
    when(roomTypeRepository.findById(31L))
        .thenReturn(Optional.of(roomTypeWithImages(31L, List.of("/a.png"), "/a.png")));
    when(roomTypeRepository.countByCode(any(), any(), any(), any())).thenReturn(0L);
    when(roomTypeRepository.countByName(any(), any(), any(), any())).thenReturn(0L);
    when(roomTypeRepository.updateSubmitted(any(RoomType.class))).thenReturn(true);

    RoomType updated = service.update(31L, new RoomTypeApplicationService.RoomTypeCommand(
        null, "VIP 大包", 20, null, null, null, null));

    assertEquals(List.of("/a.png"), updated.imageUrls());
    assertEquals("/a.png", updated.mainImageUrl());
  }

  /** 编辑换图：主图必须落在新列表里，否则 400（避免主图指向已删除的图）。 */
  @Test
  void updateRejectsMainImageOutsideSubmittedList() {
    when(roomTypeRepository.findById(31L)).thenReturn(Optional.of(roomType(31L, "VIP", "VIP 大包")));
    when(roomTypeRepository.countByCode(any(), any(), any(), any())).thenReturn(0L);
    when(roomTypeRepository.countByName(any(), any(), any(), any())).thenReturn(0L);

    BusinessException error = assertThrows(BusinessException.class, () -> service.update(31L,
        new RoomTypeApplicationService.RoomTypeCommand(null, null, null, null, null, null, null,
            List.of("/new.png"), "/old.png")));

    assertEquals(ResourceMedia.ERROR_CODE, error.getCode());
    verify(roomTypeRepository, never()).updateSubmitted(any(RoomType.class));
  }

  @Test
  void deleteRejectsRoomTypeStillReferencedByRooms() {
    when(roomTypeRepository.findById(31L)).thenReturn(Optional.of(roomType(31L, "VIP", "VIP 大包")));
    when(resourceRepository.countByRoomTypeId(31L)).thenReturn(2L);

    ApiException error = assertThrows(ApiException.class, () -> service.delete(31L));

    assertEquals(409, error.getStatus());
    assertEquals("ROOM_TYPE_IN_USE", error.getCode());
    verify(roomTypeRepository, never()).deleteById(anyLong());
  }

  @Test
  void deleteRemovesUnreferencedRoomType() {
    when(roomTypeRepository.findById(31L)).thenReturn(Optional.of(roomType(31L, "VIP", "VIP 大包")));
    when(resourceRepository.countByRoomTypeId(31L)).thenReturn(0L);
    when(roomTypeRepository.deleteById(31L)).thenReturn(true);

    service.delete(31L);

    verify(roomTypeRepository).deleteById(31L);
  }

  @Test
  void findInStoreReturnsNullForUnknownId() {
    when(roomTypeRepository.findInStore(1L, 100L, 99L)).thenReturn(Optional.empty());

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
    when(roomTypeRepository.listInStore(1L, 100L, "ACTIVE"))
        .thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    List<RoomType> rows = service.list(null, "active");

    assertEquals(1, rows.size());
    verify(roomTypeRepository).listInStore(1L, 100L, "ACTIVE");
  }

  /**
   * 线上缺陷回归：前端「房型管理」列表不带 status 调用，此前 {@code eq(cond, ..., status.trim())} 的实参
   * 会先求值，status 为 null 直接 NPE 被兜成 500。现在必须 200 返回全部（筛选值传 null = 不过滤）。
   */
  @Test
  void listWithoutStatusReturnsAllRowsWithoutFilter() {
    when(roomTypeRepository.listInStore(any(), any(), any()))
        .thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    List<RoomType> rows = assertDoesNotThrow(() -> service.list(null, null));

    assertEquals(1, rows.size());
    verify(roomTypeRepository).listInStore(1L, 100L, null);
  }

  /** status 为空串/纯空白同样按「不过滤」处理（部分客户端会拼出 status=）。 */
  @Test
  void listWithBlankStatusReturnsAllRowsWithoutFilter() {
    when(roomTypeRepository.listInStore(any(), any(), any()))
        .thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    assertEquals(1, assertDoesNotThrow(() -> service.list(null, "   ")).size());
    verify(roomTypeRepository).listInStore(1L, 100L, null);
  }

  /** 显式传本门店 storeId 且 status 为空串：只按门店过滤，仍然「返回全部房型」。 */
  @Test
  void listWithExplicitStoreAndBlankStatusReturnsAllRows() {
    when(roomTypeRepository.listInStore(any(), any(), any()))
        .thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    assertEquals(1, assertDoesNotThrow(() -> service.list(100L, "")).size());
    verify(roomTypeRepository).listInStore(1L, 100L, null);
  }

  @Test
  void listFiltersByActiveStatusWithCaseAndBlankTolerance() {
    when(roomTypeRepository.listInStore(any(), any(), any()))
        .thenReturn(List.of(roomType(31L, "VIP", "VIP 大包")));

    List<RoomType> rows = service.list(null, " active ");

    assertEquals(1, rows.size());
    // 归一为大写后再作为筛选值下发（查询条件由适配器生成）
    verify(roomTypeRepository).listInStore(1L, 100L, "ACTIVE");
  }

  @Test
  void listFiltersByDisabledStatus() {
    when(roomTypeRepository.listInStore(any(), any(), any())).thenReturn(List.of());

    assertEquals(0, service.list(null, "DISABLED").size());
    verify(roomTypeRepository).listInStore(1L, 100L, "DISABLED");
  }

  /** 非法状态值必须是可读的 400（ROOM_TYPE_STATUS_INVALID），而不是 500 或静默返回空列表。 */
  @Test
  void listRejectsInvalidStatusWith400() {
    ApiException error = assertThrows(ApiException.class, () -> service.list(null, "DELETED"));

    assertEquals(400, error.getStatus());
    assertEquals("ROOM_TYPE_STATUS_INVALID", error.getCode());
    assertEquals("房型状态筛选值只能是 ACTIVE 或 DISABLED", error.getMessage());
    verify(roomTypeRepository, never()).listInStore(any(), any(), any());
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

  @Test
  void createFailureIsAuditedWithErrorCode() {
    when(roomTypeRepository.countByCode(any(), any(), any(), any())).thenReturn(1L);

    ApiException failure = assertThrows(ApiException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", 20, 20000L, null, 0, null)));
    assertEquals("ROOM_TYPE_CODE_EXISTS", failure.getCode());

    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    assertEquals("resource.roomtype.create", captor.getValue().action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, captor.getValue().result());
    assertEquals("ROOM_TYPE_CODE_EXISTS", captor.getValue().errorCode());
  }

  @Test
  void updateFailureIsAuditedWithErrorCode() {
    when(roomTypeRepository.findById(404L)).thenReturn(Optional.empty());

    assertThrows(ApiException.class, () -> service.update(404L,
        new RoomTypeApplicationService.RoomTypeCommand(null, "新名", null, null, null, null, null)));

    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    assertEquals("resource.roomtype.update", captor.getValue().action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, captor.getValue().result());
  }

  @Test
  void createFailureCarriesBusinessExceptionCode() {
    BusinessException failure = assertThrows(BusinessException.class, () -> service.create(
        new RoomTypeApplicationService.RoomTypeCommand("VIP", "VIP 大包", null, -1L, null, null, null)));
    assertEquals("ROOM_TYPE_INVALID", failure.getCode());
  }

  private static RoomType roomType(Long id, String code, String name) {
    return new RoomType(id, 1L, 100L, code, name, 20, List.of(), null, null, null, 0,
        RoomType.STATUS_ACTIVE);
  }

  private static RoomType roomTypeWithPrices(Long id, Long unitPrice, Long serverUnitPrice) {
    return new RoomType(id, 1L, 100L, "VIP", "VIP 大包", 20, List.of(), null, unitPrice, serverUnitPrice, 0,
        RoomType.STATUS_ACTIVE);
  }

  private static RoomType roomTypeWithImages(Long id, List<String> imageUrls, String mainImageUrl) {
    return new RoomType(id, 1L, 100L, "VIP", "VIP 大包", 20, imageUrls, mainImageUrl, null, null, 0,
        RoomType.STATUS_ACTIVE);
  }
}
