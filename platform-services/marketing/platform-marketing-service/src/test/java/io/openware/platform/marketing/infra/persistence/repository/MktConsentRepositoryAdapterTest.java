package io.openware.platform.marketing.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.marketing.domain.consent.model.MktConsent;
import io.openware.platform.marketing.infra.persistence.mapper.ConsentMapper;
import io.openware.platform.marketing.infra.persistence.po.MktConsentPo;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Consent CAS 的 SQL 组装：更新必须带 version 谓词并在 SQL 内自增；0 行即冲突。
 */
class MktConsentRepositoryAdapterTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MktConsentPo.class);
  }

  @SuppressWarnings("unchecked")
  @Test
  void updateUsesVersionPredicateAndIncrementsItInSql() {
    ConsentMapper mapper = mock(ConsentMapper.class);
    when(mapper.update(isNull(), any())).thenReturn(1);
    MktConsentRepositoryAdapter adapter = new MktConsentRepositoryAdapter(mapper);
    MktConsent current = new MktConsent(7L, 100L, 1000L, "MARKETING", "APP", "1.0",
        true, "BUSINESS", LocalDateTime.now(), null, 3);

    adapter.save(current.changeGrant(false, "1.0", LocalDateTime.now()));

    ArgumentCaptor<Wrapper<MktConsentPo>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).update(isNull(), captor.capture());
    LambdaUpdateWrapper<MktConsentPo> wrapper = (LambdaUpdateWrapper<MktConsentPo>) captor.getValue();
    assertTrue(wrapper.getSqlSegment().contains("version"));
    assertTrue(wrapper.getSqlSet().contains("version = version + 1"));
  }

  @Test
  void zeroRowsMeansVersionConflict() {
    ConsentMapper mapper = mock(ConsentMapper.class);
    when(mapper.update(isNull(), any())).thenReturn(0);
    MktConsentRepositoryAdapter adapter = new MktConsentRepositoryAdapter(mapper);
    MktConsent current = new MktConsent(7L, 100L, 1000L, "MARKETING", "APP", "1.0",
        true, "BUSINESS", LocalDateTime.now(), null, 3);

    IllegalStateException conflict = assertThrows(IllegalStateException.class,
        () -> adapter.save(current.changeGrant(false, "1.0", LocalDateTime.now())));

    assertEquals("MKT_CONSENT_VERSION_CONFLICT", conflict.getMessage());
  }
}
