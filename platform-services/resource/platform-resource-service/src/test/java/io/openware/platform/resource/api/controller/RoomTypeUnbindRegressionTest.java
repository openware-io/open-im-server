package io.openware.platform.resource.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.resource.application.RoomTypeApplicationService;
import io.openware.platform.resource.infra.persistence.mapper.ResourceMapper;
import io.openware.platform.resource.infra.persistence.mapper.RoomTypeMapper;
import io.openware.platform.resource.infra.persistence.po.ResourcePo;
import io.openware.platform.resource.infra.persistence.po.RoomTypePo;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 缺陷链路回归（进程内端到端）：
 * {@code PUT /api/v1/admin/resources/{id}} body {@code {"roomTypeId":0}} 解绑房型后，
 * {@code DELETE /api/v1/admin/resources/types/{id}} 必须不再 409 {@code ROOM_TYPE_IN_USE}、能删成功。
 *
 * <p>资源服务没有 H2 测试库（集成测试走真库），这里用一个「按 SET 子句真正改内存行」的假 Mapper 顶替数据库：
 * {@code update(entity, wrapper)} 只把 wrapper 显式 SET 的列写进内存行，{@code selectCount} 则按 wrapper 的
 * 条件片段在内存行上求值，{@code selectById} 返回副本（否则控制器直接改内存对象就绕过了「SQL 有没有真的写 NULL」）。
 * 因此 {@code updateById} 的 NOT_NULL 跳过 null 行为也能被忠实复现——旧实现下本用例必然失败。
 */
class RoomTypeUnbindRegressionTest {

  /** SET 片段：{@code room_type_id=#{ew.paramNameValuePairs.MPGENVAL1}}（JSON 列还会带 typeHandler）。 */
  private static final Pattern SET_CLAUSE =
      Pattern.compile("([a-z_]+)=#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)[^}]*}");
  /** 条件片段：{@code room_type_id = #{ew.paramNameValuePairs.MPGENVAL3}}。 */
  private static final Pattern WHERE_CLAUSE =
      Pattern.compile("([a-z_]+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)[^}]*}");

  private static final long TENANT_ID = 1L;
  private static final long STORE_ID = 100L;
  private static final long RESOURCE_ID = 7L;
  private static final long ROOM_TYPE_ID = 31L;

  private final Map<Long, ResourcePo> resources = new LinkedHashMap<>();
  private final Map<Long, RoomTypePo> roomTypes = new LinkedHashMap<>();

  private ResourceMapper resourceMapper;
  private RoomTypeMapper roomTypeMapper;
  private ResourceController controller;
  private RoomTypeApplicationService roomTypeService;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ResourcePo.class);
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RoomTypePo.class);
    resources.clear();
    roomTypes.clear();
    resources.put(RESOURCE_ID, resource(RESOURCE_ID, "V07", ROOM_TYPE_ID));
    roomTypes.put(ROOM_TYPE_ID, roomType(ROOM_TYPE_ID, "VIP", "VIP 大包"));

    resourceMapper = mock(ResourceMapper.class);
    roomTypeMapper = mock(RoomTypeMapper.class);
    when(resourceMapper.selectById(any())).thenAnswer(invocation -> copy(resources.get(invocation.getArgument(0))));
    when(resourceMapper.selectCount(any())).thenAnswer(invocation -> countMatching(invocation.getArgument(0)));
    // 新实现：显式 SET，值为 null 的列同样写库（真正清空）。
    when(resourceMapper.update(any(), any())).thenAnswer(invocation -> {
      applyExplicitSet(invocation.getArgument(1));
      return 1;
    });
    // 旧实现：updateById 按 NOT_NULL 策略跳过 null 字段 → 清空不落库。保留以便忠实复现缺陷。
    when(resourceMapper.updateById(any(ResourcePo.class))).thenAnswer(invocation -> {
      applyNotNullFields(invocation.getArgument(0));
      return 1;
    });
    when(roomTypeMapper.selectById(any())).thenAnswer(invocation -> roomTypes.get(invocation.getArgument(0)));
    when(roomTypeMapper.deleteById(ROOM_TYPE_ID)).thenAnswer(invocation -> {
      roomTypes.remove(ROOM_TYPE_ID);
      return 1;
    });

    roomTypeService = new RoomTypeApplicationService(roomTypeMapper, resourceMapper);
    controller = new ResourceController(resourceMapper, roomTypeService);
    TenantContextHolder.set(new TenantContext(TENANT_ID, null, STORE_ID, 1L, 1, List.of("resource.manage")));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /** 解绑前 409 → PUT roomTypeId=0 后房型不再被引用 → DELETE 成功。 */
  @Test
  void unbindRoomTypeMakesRoomTypeDeletable() {
    ApiException inUse = assertThrows(ApiException.class, () -> roomTypeService.delete(ROOM_TYPE_ID));
    assertEquals(409, inUse.getStatus());
    assertEquals("ROOM_TYPE_IN_USE", inUse.getCode());
    assertFalse(roomTypes.isEmpty(), "409 时房型不能被删掉");

    ResourcePo updated = controller.update(RESOURCE_ID,
        new ResourceController.UpdateResourceRequest(null, null, null, 0L, null, null, null));

    assertNull(updated.getRoomTypeId(), "响应里的房型必须已清空");
    assertNull(resources.get(RESOURCE_ID).getRoomTypeId(), "库里 room_type_id 必须真的落库为 NULL");

    roomTypeService.delete(ROOM_TYPE_ID);

    verify(roomTypeMapper).deleteById(ROOM_TYPE_ID);
    assertFalse(roomTypes.containsKey(ROOM_TYPE_ID), "解绑后房型应能删掉");
    verify(resourceMapper, never()).updateById(any(ResourcePo.class));
  }

  /** 反例保护：还有别的包厢绑着该房型时，DELETE 依旧 409（修的是「真清空」，不是放行删除）。 */
  @Test
  void deleteStillRejectsWhenAnotherRoomBindsTheRoomType() {
    resources.put(8L, resource(8L, "V08", ROOM_TYPE_ID));

    controller.update(RESOURCE_ID,
        new ResourceController.UpdateResourceRequest(null, null, null, 0L, null, null, null));

    ApiException error = assertThrows(ApiException.class, () -> roomTypeService.delete(ROOM_TYPE_ID));
    assertEquals(409, error.getStatus());
    assertEquals("ROOM_TYPE_IN_USE", error.getCode());
    verify(roomTypeMapper, never()).deleteById(any(Long.class));
  }

  /** 假 Mapper 的读取返回副本：控制器在返回对象上改字段不能算「落库」。 */
  private static ResourcePo copy(ResourcePo row) {
    if (row == null) {
      return null;
    }
    ResourcePo copy = new ResourcePo();
    copy.setId(row.getId());
    copy.setTenantId(row.getTenantId());
    copy.setStoreId(row.getStoreId());
    copy.setResourceType(row.getResourceType());
    copy.setResourceCode(row.getResourceCode());
    copy.setName(row.getName());
    copy.setAreaName(row.getAreaName());
    copy.setCapacity(row.getCapacity());
    copy.setRoomTypeId(row.getRoomTypeId());
    copy.setStatus(row.getStatus());
    copy.setImageUrls(row.getImageUrls());
    copy.setMainImageUrl(row.getMainImageUrl());
    copy.setDescription(row.getDescription());
    copy.setUpdatedAt(row.getUpdatedAt());
    return copy;
  }

  /** 模拟 {@code update(entity, wrapper)}：按 WHERE 命中内存行，只写入 wrapper 显式列出的列。 */
  private void applyExplicitSet(LambdaUpdateWrapper<ResourcePo> update) {
    Map<String, Object> conditions = conditionsOf(update);
    Map<String, Object> params = update.getParamNameValuePairs();
    Matcher matcher = SET_CLAUSE.matcher(update.getSqlSet());
    int applied = 0;
    while (matcher.find()) {
      Object value = params.get(matcher.group(2));
      String column = matcher.group(1);
      for (ResourcePo row : resources.values()) {
        if (matches(row, conditions)) {
          applyColumn(row, column, value);
        }
      }
      applied++;
    }
    if (applied == 0) {
      throw new IllegalStateException("update 没有下发任何 SET 列：" + update.getSqlSet());
    }
  }

  /** 模拟旧实现的 {@code updateById}：MyBatis-Plus 默认按 NOT_NULL 策略跳过 null 字段（清空因此丢失）。 */
  private void applyNotNullFields(ResourcePo patch) {
    ResourcePo row = resources.get(patch.getId());
    if (patch.getName() != null) {
      row.setName(patch.getName());
    }
    if (patch.getAreaName() != null) {
      row.setAreaName(patch.getAreaName());
    }
    if (patch.getCapacity() != null) {
      row.setCapacity(patch.getCapacity());
    }
    if (patch.getRoomTypeId() != null) {
      row.setRoomTypeId(patch.getRoomTypeId());
    }
    if (patch.getImageUrls() != null) {
      row.setImageUrls(patch.getImageUrls());
    }
    if (patch.getMainImageUrl() != null) {
      row.setMainImageUrl(patch.getMainImageUrl());
    }
    if (patch.getDescription() != null) {
      row.setDescription(patch.getDescription());
    }
    if (patch.getUpdatedAt() != null) {
      row.setUpdatedAt(patch.getUpdatedAt());
    }
  }

  /** 取 wrapper 的等值条件（列名 → 绑定值），无法解析时直接失败，避免假 Mapper 悄悄放行。 */
  private static Map<String, Object> conditionsOf(LambdaUpdateWrapper<ResourcePo> wrapper) {
    String sqlSegment = wrapper.getSqlSegment();
    Map<String, Object> conditions = new LinkedHashMap<>();
    Matcher matcher = WHERE_CLAUSE.matcher(sqlSegment == null ? "" : sqlSegment);
    while (matcher.find()) {
      conditions.put(matcher.group(1), wrapper.getParamNameValuePairs().get(matcher.group(2)));
    }
    if (conditions.isEmpty()) {
      throw new IllegalStateException("假 Mapper 无法解析更新条件：" + sqlSegment);
    }
    return conditions;
  }

  /** 模拟 {@code selectCount(wrapper)}：按 wrapper 的条件片段统计内存行。 */
  private long countMatching(LambdaQueryWrapper<ResourcePo> query) {
    Map<String, Object> conditions = new LinkedHashMap<>();
    String sqlSegment = query.getSqlSegment();
    Matcher matcher = WHERE_CLAUSE.matcher(sqlSegment == null ? "" : sqlSegment);
    while (matcher.find()) {
      conditions.put(matcher.group(1), query.getParamNameValuePairs().get(matcher.group(2)));
    }
    if (conditions.isEmpty()) {
      throw new IllegalStateException("假 Mapper 无法解析查询条件：" + sqlSegment);
    }
    return resources.values().stream().filter(row -> matches(row, conditions)).count();
  }

  private static boolean matches(ResourcePo row, Map<String, Object> conditions) {
    for (Map.Entry<String, Object> condition : conditions.entrySet()) {
      if (!Objects.equals(columnValue(row, condition.getKey()), condition.getValue())) {
        return false;
      }
    }
    return true;
  }

  /** 假 Mapper 只认识本用例真正会读写到的列：出现别的列直接失败，防止悄悄写错列。 */
  private static Object columnValue(ResourcePo row, String column) {
    return switch (column) {
      case "id" -> row.getId();
      case "tenant_id" -> row.getTenantId();
      case "store_id" -> row.getStoreId();
      case "room_type_id" -> row.getRoomTypeId();
      default -> throw new IllegalStateException("假 Mapper 未支持的查询列：" + column);
    };
  }

  private static void applyColumn(ResourcePo row, String column, Object value) {
    switch (column) {
      case "name" -> row.setName((String) value);
      case "area_name" -> row.setAreaName((String) value);
      case "capacity" -> row.setCapacity((Integer) value);
      case "room_type_id" -> row.setRoomTypeId((Long) value);
      case "image_urls" -> row.setImageUrls(imageUrls(value));
      case "main_image_url" -> row.setMainImageUrl((String) value);
      case "description" -> row.setDescription((String) value);
      case "updated_at" -> row.setUpdatedAt((LocalDateTime) value);
      default -> throw new IllegalStateException("假 Mapper 未支持的写入列：" + column);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> imageUrls(Object value) {
    return (List<String>) value;
  }

  private static ResourcePo resource(Long id, String code, Long roomTypeId) {
    ResourcePo po = new ResourcePo();
    po.setId(id);
    po.setTenantId(TENANT_ID);
    po.setStoreId(STORE_ID);
    po.setResourceType("KTV_ROOM");
    po.setResourceCode(code);
    po.setName("包厢 " + id);
    po.setRoomTypeId(roomTypeId);
    po.setStatus("ENABLED");
    return po;
  }

  private static RoomTypePo roomType(Long id, String code, String name) {
    RoomTypePo po = new RoomTypePo();
    po.setId(id);
    po.setTenantId(TENANT_ID);
    po.setStoreId(STORE_ID);
    po.setCode(code);
    po.setName(name);
    po.setStatus("ACTIVE");
    return po;
  }
}
