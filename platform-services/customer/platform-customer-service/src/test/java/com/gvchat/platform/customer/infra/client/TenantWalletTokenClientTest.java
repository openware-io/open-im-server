package com.gvchat.platform.customer.infra.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gvchat.platform.customer.infra.client.TenantWalletTokenClient.WalletTokenConfig;
import org.junit.jupiter.api.Test;

/**
 * 租户代币展示配置客户端：失败一律回退缺省 {@code A380币 / 100} 且不抛异常
 * （C 端余额/流水展示不能因租户配置读取失败而失败）。
 */
class TenantWalletTokenClientTest {

  /** 无租户 ID（无上下文/非法）不发起调用，直接回退缺省。 */
  @Test
  void returnsDefaultsWithoutTenantId() {
    TenantWalletTokenClient client = new TenantWalletTokenClient("http://127.0.0.1:1", "platform-customer-service",
        "0123456789abcdef0123456789abcdef");

    assertEquals(WalletTokenConfig.DEFAULT, client.resolve(null));
    assertEquals(WalletTokenConfig.DEFAULT, client.resolve(0L));
    assertEquals(WalletTokenConfig.DEFAULT, client.resolve(-1L));
  }

  /** 租户服务不可达（连接被拒）时只回退缺省，绝不抛出。 */
  @Test
  void fallsBackWhenTenantServiceIsUnreachable() {
    TenantWalletTokenClient client = new TenantWalletTokenClient("http://127.0.0.1:1", "platform-customer-service",
        "0123456789abcdef0123456789abcdef");

    WalletTokenConfig config = client.resolve(100L);

    assertEquals("A380币", config.brandName());
    assertEquals(100L, config.ratio());
  }

  /** 缺省配置就是 A380币 / 100（与 tenant-service 的缺省口径一致）。 */
  @Test
  void defaultConfigMatchesTenantServiceDefaults() {
    assertEquals("A380币", WalletTokenConfig.DEFAULT.brandName());
    assertEquals(100L, WalletTokenConfig.DEFAULT.ratio());
  }
}
