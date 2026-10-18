package io.openware.platform.tenant.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.tenant.domain.configuration.model.TenantConfig;
import io.openware.platform.tenant.infra.persistence.mapper.InternalTenantConfigMapper;
import io.openware.platform.tenant.infra.persistence.mapper.TenantConfigMapper;
import io.openware.platform.tenant.infra.persistence.po.TenantConfigPo;
import java.util.List;
import java.util.Locale;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * `TenantConfig.upsert` 的并发保护：更新必须带 `version = ?` 谓词并自增版本，
 * 否则 version 只是装饰字段，并发改同一配置会静默覆盖。
 */
class TenantConfigRepositoryAdapterTest {

  @BeforeAll
  static void registerTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    assistant.setCurrentNamespace(TenantConfigRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, TenantConfigPo.class);
  }

  @Test
  void upsertUpdateUsesVersionCasAndIncrementsIt() {
    TenantConfigMapper mapper = mock(TenantConfigMapper.class);
    TenantConfigRepositoryAdapter adapter = new TenantConfigRepositoryAdapter(mapper,
        mock(InternalTenantConfigMapper.class));
    TenantConfigPo existing = new TenantConfigPo();
    existing.setId(9L);
    existing.setTenantId(1L);
    existing.setStoreId(0L);
    existing.setBusinessType("");
    existing.setConfigKey(TenantConfig.KEY_CURRENCY);
    existing.setConfigValue("USD");
    existing.setStatus(TenantConfig.STATUS_ACTIVE);
    existing.setVersion(3);
    when(mapper.selectList(any())).thenReturn(List.of(existing));
    when(mapper.update(isNull(), any())).thenReturn(1);

    TenantConfig saved = adapter.upsert(TenantConfig.tenantLevel(TenantConfig.KEY_CURRENCY, 1L, "CNY"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<LambdaUpdateWrapper<TenantConfigPo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(mapper).update(isNull(), captor.capture());
    LambdaUpdateWrapper<TenantConfigPo> wrapper = captor.getValue();
    String where = wrapper.getTargetSql().toLowerCase(Locale.ROOT);
    assertTrue(where.contains("version ="), where);
    assertTrue(wrapper.getSqlSet().toLowerCase(Locale.ROOT).contains("version = version + 1"));
    assertEquals(4, saved.version());
    assertEquals("CNY", saved.configValue());
  }
}
