package io.openware.platform.tenant.infra.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.model.PermissionCatalogSnapshot;
import io.openware.platform.tenant.domain.iam.model.Role;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 权限目录矩阵缓存：版本键、Redis 一级 + 本地兜底、命中/未命中。 */
class PermissionCatalogCacheTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final PermissionCatalogCache cache = new PermissionCatalogCache(redis);

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(valueOps);
  }

  @Test
  void put_writesLocalAndRedisUnderVersionedKeyWithTtl() throws Exception {
    PermissionCatalogSnapshot snapshot = snapshot("v1");

    cache.put("v1", snapshot);

    assertNotNull(cache.get("v1"));
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(valueOps).set(eq("iam:catalog:matrix:v1"), json.capture(), eq(Duration.ofMinutes(10)));
    assertEquals(snapshot, objectMapper.readValue(json.getValue(), PermissionCatalogSnapshot.class));
  }

  @Test
  void get_returnsLocalHitWithoutRedisRead() {
    PermissionCatalogSnapshot snapshot = snapshot("v1");
    cache.put("v1", snapshot);

    PermissionCatalogSnapshot result = cache.get("v1");

    assertEquals(snapshot, result);
    verify(valueOps, never()).get(anyString());
  }

  @Test
  void get_fallsBackToRedisAndPrimesLocalCache() throws Exception {
    PermissionCatalogSnapshot snapshot = snapshot("v2");
    when(valueOps.get("iam:catalog:matrix:v2")).thenReturn(objectMapper.writeValueAsString(snapshot));

    PermissionCatalogSnapshot first = cache.get("v2");
    PermissionCatalogSnapshot second = cache.get("v2");

    assertEquals(snapshot, first);
    assertEquals(snapshot, second);
    verify(valueOps).get("iam:catalog:matrix:v2");
  }

  @Test
  void get_returnsNullWhenVersionMissingEverywhere() {
    when(valueOps.get("iam:catalog:matrix:missing")).thenReturn(null);

    assertNull(cache.get("missing"));
  }

  private PermissionCatalogSnapshot snapshot(String version) {
    Permission permission = new Permission(1L, "tenant.store.manage", "tenant", "store", "manage",
        "门店管理", "TENANT", "core", "TENANT", "store", "ACTIVE");
    Role role = new Role(2L, null, "tenant.owner", "租户老板", "PRESET", "TENANT", null, null, "ACTIVE");
    return new PermissionCatalogSnapshot(List.of(permission), List.of(role),
        Map.of(2L, List.of(1L)), version, "2026-10-29T00:00:00Z");
  }
}
