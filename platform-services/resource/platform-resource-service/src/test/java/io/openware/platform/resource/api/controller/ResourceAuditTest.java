package io.openware.platform.resource.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.resource.application.RoomTypeApplicationService;
import io.openware.platform.resource.domain.roomtype.model.RoomType;
import io.openware.platform.resource.application.ResourceQueryApplicationService;
import io.openware.platform.resource.domain.resource.model.Resource;
import io.openware.platform.resource.domain.resource.repository.ResourceRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 包厢（资源）增改的统一审计留痕：动作码、资源标识与变更前后值。
 *
 * <p>统一审计平台要求「关键写操作必须可回溯」；包厢的房型/区域/容量直接决定开台计费与占用门禁，
 * 所以创建与编辑都必须落一条 audit 记录，且 detailJson 必须是合法 JSON 并带 before/after。
 */
class ResourceAuditTest {

  private ResourceRepository resourceRepository;
  private ResourceQueryApplicationService resourceQuery;
  private RoomTypeApplicationService roomTypeService;
  private AuditClient auditClient;
  private ResourceController controller;

  @BeforeEach
  void setUp() {
    resourceRepository = mock(ResourceRepository.class);
    resourceQuery = mock(ResourceQueryApplicationService.class);
    roomTypeService = mock(RoomTypeApplicationService.class);
    auditClient = mock(AuditClient.class);
    controller = new ResourceController(resourceRepository, resourceQuery, roomTypeService, auditClient);
    when(resourceQuery.enrichWithRoomTypes(any())).thenAnswer(i -> i.getArgument(0));
    TenantContextHolder.set(new TenantContext(1L, null, 100L, 1L, 1, List.of("resource.manage")));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void createRecordsAuditWithStoreTypeAndRoomType() {
    when(resourceRepository.countByResourceCode(any(), any(), any(), any())).thenReturn(0L);
    when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> {
      Resource resource = invocation.getArgument(0);
      return new Resource(7L, resource.tenantId(), resource.storeId(), resource.resourceType(),
          resource.resourceCode(), resource.name(), resource.areaName(), resource.parentId(),
          resource.capacity(), resource.roomTypeId(), resource.status(), resource.cleaningStatus(),
          resource.cleaningStartedAt(), resource.imageUrls(), resource.mainImageUrl(),
          resource.description(), null, null, null, null);
    });
    // 房型必须属于本门店：控制器会对入参房型做归属校验。
    RoomType roomType = new RoomType(31L, 1L, 100L, "VIP", "VIP 大包", 10, List.of(), null,
        100L, 0L, 1, RoomType.STATUS_ENABLED);
    when(roomTypeService.findInStore(31L)).thenReturn(roomType);

    controller.create(new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "A-101", "A101",
        "大堂", 8, 31L, null, null, null));
    AuditClient.AuditRecord record = capturedRecord();
    assertEquals("resource.create", record.action());
    assertEquals("res_resource", record.resourceType());
    assertEquals("7", record.resourceId());
    assertEquals("A101", record.resourceName());
    assertNotNull(record.idempotencyKey());
    assertTrue(record.detailJson().contains("\"storeId\":100"), record.detailJson());
    assertTrue(record.detailJson().contains("\"resourceType\":\"KTV_ROOM\""), record.detailJson());
    assertTrue(record.detailJson().contains("\"resourceCode\":\"A-101\""), record.detailJson());
    assertTrue(record.detailJson().contains("\"roomTypeId\":31"), record.detailJson());
  }

  /** 解绑房型（roomTypeId=0）这类「清空」变更必须能在审计里看到前后值：before=31、after=null。 */
  @Test
  void updateRecordsAuditWithBeforeAndAfterValues() {
    Resource existing = new Resource(9L, 1L, 100L, "KTV_ROOM", "A-101", "A101", "大堂", null, 8, 31L,
        Resource.STATUS_ENABLED, Resource.CLEANING_IDLE, null, List.of(), null, null, null, null, null, null);
    when(resourceRepository.findById(9L)).thenReturn(java.util.Optional.of(existing));

    controller.update(9L, new ResourceController.UpdateResourceRequest(null, "  ", 10, 0L, null, null, null));

    AuditClient.AuditRecord record = capturedRecord();
    assertEquals("resource.update", record.action());
    assertEquals("res_resource", record.resourceType());
    assertEquals("9", record.resourceId());
    String detail = record.detailJson();
    assertTrue(detail.contains("\"before\":{\"name\":\"A101\",\"areaName\":\"大堂\",\"capacity\":8,\"roomTypeId\":31}"),
        detail);
    assertTrue(detail.contains("\"after\":{\"name\":\"A101\",\"areaName\":null,\"capacity\":10,\"roomTypeId\":null}"),
        detail);
  }

  /**
   * ③ 失败留痕：资源创建失败（编号重复 409）除抛异常外必须落一条 FAILURE，
   * 且错误码使用既有 ApiException 稳定码（后台可据此检索「为什么建不出来」）。
   */
  @Test
  void createFailureRecordsFailureAuditWithErrorCode() {
    when(resourceRepository.countByResourceCode(any(), any(), any(), any())).thenReturn(1L);

    ApiException failure = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
        () -> controller.create(new ResourceController.CreateResourceRequest(1L, 100L, "KTV_ROOM", "A-101",
            "A101", "大堂", 8, null, null, null, null)));
    assertEquals("RESOURCE_CODE_EXISTS", failure.getCode());

    AuditClient.AuditRecord record = capturedRecord();
    assertEquals("resource.create", record.action());
    assertEquals("res_resource", record.resourceType());
    assertEquals("A-101", record.resourceId());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("RESOURCE_CODE_EXISTS", record.errorCode());
  }

  /** ③ 失败留痕：资源编辑失败（资源不存在 404）同样必须留痕。 */
  @Test
  void updateFailureRecordsFailureAuditWithErrorCode() {
    when(resourceRepository.findById(404L)).thenReturn(java.util.Optional.empty());

    org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
        () -> controller.update(404L, new ResourceController.UpdateResourceRequest("新名", null, null, null,
            null, null, null)));

    AuditClient.AuditRecord record = capturedRecord();
    assertEquals("resource.update", record.action());
    assertEquals("404", record.resourceId());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("RESOURCE_NOT_FOUND", record.errorCode());
  }

  private AuditClient.AuditRecord capturedRecord() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }
}
