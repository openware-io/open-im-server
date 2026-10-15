package io.openware.platform.resource.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.openware.platform.resource.infra.persistence.mapper.ResourceMapper;
import io.openware.platform.resource.infra.persistence.po.ResourcePo;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

/**
 * {@link ResourceRepositoryAdapter} 的持久化语义测试。
 *
 * <p>这里验证的是**清空是否真的进了 SQL**。这一层保真度原先只能靠
 * {@code RoomTypeUnbindRegressionTest} 里一个手工解析 SET 子句的假 Mapper 提供，而那时
 * {@code ResourceController} 自己在下发 SQL；控制器迁到端口之后，写下这些列的是适配器，因此断言也应当
 * 落在适配器测试里——在原来的位置它测的是控制器，在这里它测的是真正负责生成 SQL 的一层。
 *
 * <p>背景缺陷：MyBatis-Plus 默认 {@code FieldStrategy.NOT_NULL} 会把 null 字段整列跳过。
 * 若解绑改用 {@code updateById}（或"整体保存"），{@code room_type_id} 会从 UPDATE 中消失，
 * 解绑静默失效，删除房型永远 409 {@code ROOM_TYPE_IN_USE}。
 */
class ResourceRepositoryAdapterTest {

  private ResourceMapper mapper;
  private ResourceRepositoryAdapter adapter;

  @BeforeEach
  void setUp() {
    // LambdaUpdateWrapper.set(Po::getXxx, ...) 的列名解析是懒求值的：纯单元测试没有 MyBatis 上下文，
    // 必须先注册实体 TableInfo，SET 子句才能渲染出列名。
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ResourcePo.class);
    mapper = mock(ResourceMapper.class);
    adapter = new ResourceRepositoryAdapter(mapper);
  }

  /** 解绑必须显式把 {@code room_type_id} 写进 SET 子句（且绑定值为 null）。 */
  @Test
  void clearRoomTypeBindingWritesNullColumnExplicitly() {
    when(mapper.update(isNull(), any())).thenReturn(1);

    adapter.clearRoomTypeBinding(7L);

    LambdaUpdateWrapper<ResourcePo> wrapper = capturedWrapper();
    assertTrue(wrapper.getSqlSet().contains("room_type_id"),
        "解绑必须显式 SET room_type_id，否则 NOT_NULL 策略会整列跳过: " + wrapper.getSqlSet());
    assertTrue(wrapper.getSqlSet().contains("updated_at"),
        "更新必须同时刷新 updated_at: " + wrapper.getSqlSet());
    assertTrue(wrapper.getParamNameValuePairs().values().stream().anyMatch(java.util.Objects::isNull),
        "room_type_id 的绑定值必须为 null，否则不是解绑");
  }

  /** 换绑到另一房型同样要显式写列（含新的非空值）。 */
  @Test
  void updateRoomTypeBindingWritesColumnExplicitly() {
    when(mapper.update(isNull(), any())).thenReturn(1);

    adapter.updateRoomTypeBinding(7L, 41L);

    LambdaUpdateWrapper<ResourcePo> wrapper = capturedWrapper();
    assertTrue(wrapper.getSqlSet().contains("room_type_id"), wrapper.getSqlSet());
    assertTrue(wrapper.getParamNameValuePairs().containsValue(41L),
        "新绑定值必须进入参数表: " + wrapper.getParamNameValuePairs());
  }

  /** 基础信息更新：空值（清空区域/描述）同样必须真的进 SET。 */
  @Test
  void updateDetailsWritesNullValuesExplicitly() {
    when(mapper.update(isNull(), any())).thenReturn(1);

    adapter.updateDetails(7L, "A101", null, 8, null);

    LambdaUpdateWrapper<ResourcePo> wrapper = capturedWrapper();
    for (String column : List.of("name", "area_name", "capacity", "description")) {
      assertTrue(wrapper.getSqlSet().contains(column),
          "列 " + column + " 必须在 SET 中: " + wrapper.getSqlSet());
    }
    assertTrue(wrapper.getParamNameValuePairs().values().stream().anyMatch(java.util.Objects::isNull),
        "清空区域/描述必须写 null，而不是跳过该列");
  }

  /** 图片列是 JSON：SET 必须带上 typeHandler，否则参数绑定会报「找不到 TypeHandler」。 */
  @Test
  void updateMediaCarriesJsonTypeHandler() {
    when(mapper.update(isNull(), any())).thenReturn(1);

    adapter.updateMedia(7L, List.of("/a.png"), "/a.png");

    LambdaUpdateWrapper<ResourcePo> wrapper = capturedWrapper();
    assertTrue(wrapper.getSqlSet().contains("image_urls"), wrapper.getSqlSet());
    assertTrue(wrapper.getSqlSet().contains("typeHandler"),
        "image_urls 是 JSON 列，SET 必须显式带 typeHandler: " + wrapper.getSqlSet());
  }

  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<ResourcePo> capturedWrapper() {
    ArgumentCaptor<LambdaUpdateWrapper<ResourcePo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(mapper).update(isNull(), captor.capture());
    return captor.getValue();
  }
}
