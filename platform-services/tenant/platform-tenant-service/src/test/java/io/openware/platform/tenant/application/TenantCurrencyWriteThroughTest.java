package io.openware.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.currency.Currency;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigReader;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigRepository;
import io.openware.platform.tenant.domain.currency.repository.WalletAccountCurrencyPort;
import io.openware.platform.tenant.infra.persistence.repository.StoreRepositoryAdapter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 真实 H2 + MyBatis：租户切币种时，门店 default_currency 写穿与配置写入必须在同一事务。
 */
@SpringBootTest(
    classes = TenantCurrencyWriteThroughTest.TestConfig.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:tenant-currency;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
    })
class TenantCurrencyWriteThroughTest {

  private static final long TENANT_ID = 100L;

  @MockitoBean
  private TenantConfigReader tenantConfigReader;

  @MockitoBean
  private TenantConfigRepository tenantConfigRepository;

  @MockitoBean
  private WalletAccountCurrencyPort walletAccountCurrencyPort;

  @Autowired
  private TenantCurrencyApplicationService service;

  @Autowired
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.execute("DROP TABLE IF EXISTS tnt_store");
    jdbc.execute("CREATE TABLE tnt_store ("
        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
        + "default_currency VARCHAR(3) NOT NULL, updated_at TIMESTAMP)");
    jdbc.update("INSERT INTO tnt_store (tenant_id, default_currency) VALUES (?, 'USD')", TENANT_ID);
    jdbc.update("INSERT INTO tnt_store (tenant_id, default_currency) VALUES (?, 'USD')", TENANT_ID);
    when(tenantConfigReader.findValue(TENANT_ID, 0L, "currency")).thenReturn(Optional.of("USD"));
    when(walletAccountCurrencyPort.listNonZeroInOtherCurrency(TENANT_ID, "CNY")).thenReturn(List.of());
  }

  @Test
  void switchCurrencyWritesThroughEveryStoreAndPersistsConfig() {
    TenantCurrencyApplicationService.CurrencySwitchResult result =
        service.switchCurrency(TENANT_ID, Currency.CNY, false);

    assertEquals(2, result.storesUpdated());
    assertEquals(List.of("CNY", "CNY"), jdbc.queryForList(
        "SELECT default_currency FROM tnt_store WHERE tenant_id = ? ORDER BY id", String.class, TENANT_ID));
  }

  @Test
  void storeWriteThroughRollsBackWhenConfigWriteFails() {
    when(tenantConfigRepository.upsert(any())).thenThrow(new IllegalStateException("config write failed"));

    assertThrows(IllegalStateException.class,
        () -> service.switchCurrency(TENANT_ID, Currency.CNY, false));

    assertEquals(List.of("USD", "USD"), jdbc.queryForList(
        "SELECT default_currency FROM tnt_store WHERE tenant_id = ? ORDER BY id", String.class, TENANT_ID));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @MapperScan("io.openware.platform.tenant.infra.persistence.mapper")
  @Import({StoreRepositoryAdapter.class, TenantCurrencyApplicationService.class})
  static class TestConfig {
  }
}
