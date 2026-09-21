package com.gvchat.platform.resource.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.resource.application.RoomTypeApplicationService;
import com.gvchat.platform.resource.infra.persistence.mapper.ResourceMapper;
import com.gvchat.platform.resource.infra.persistence.po.ResourcePo;
import com.gvchat.platform.resource.infra.persistence.po.RoomTypePo;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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

  private ResourceMapper resourceMapper;
  private RoomTypeApplicationService roomTypeService;
  private AuditClient auditClient;
  private ResourceController controller;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ResourcePo.class);
    resourceMapper = mock(ResourceMapper.class);
    roomTypeService = mock(RoomTypeApplicationService.class);
    auditClient = mock(AuditClient.class);
    controller = new ResourceController(resourceMapper, roomTypeService, auditClient);
    when(roomTypeService.listByIds(any())).thenReturn(List.of());
    TenantContextHolder.set(new TenantContext(1L, null, 100L, 1L, 1, List.of("resource.manage")));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void createRecordsAuditWithStoreTypeAndRoomType() {
    when(resourceMapper.selectCount(any())).thenReturn(0L);
    when(resourceMapper.insert(any(ResourcePo.class))).thenAnswer(invocation -> {
      ResourcePo po = invocation.getArgument(0);
      po.setId(7L);
      return 1;
    });
    // 房型必须属于本门店：控制器会对入参房型做归属校验。
    RoomTypePo roomType = new RoomTypePo();
    roomType.setId(31L);
    roomType.setStoreId(100L);
    roomType.setCode("VIP");
    roomType.setName("VIP 大包");
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
    ResourcePo existing = new ResourcePo();
    existing.setId(9L);
    existing.setTenantId(1L);
    existing.setStoreId(100L);
    existing.setResourceType("KTV_ROOM");
    existing.setResourceCode("A-101");
    existing.setName("A101");
    existing.setAreaName("大堂");
    existing.setCapacity(8);
    existing.setRoomTypeId(31L);
    existing.setStatus("ENABLED");
    when(resourceMapper.selectById(9L)).thenReturn(existing);

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
    when(resourceMapper.selectCount(any())).thenReturn(1L);

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
    when(resourceMapper.selectById(404L)).thenReturn(null);

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
