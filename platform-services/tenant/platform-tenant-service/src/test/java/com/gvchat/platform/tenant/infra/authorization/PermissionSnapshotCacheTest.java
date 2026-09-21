package com.gvchat.platform.tenant.infra.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.platform.tenant.domain.authorization.PermissionSnapshot;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** IAM 权限快照缓存：Redis 一级缓存 + 本地 ConcurrentHashMap 兜底、命中/未命中/逐出。 */
class PermissionSnapshotCacheTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final PermissionSnapshotCache cache = new PermissionSnapshotCache(redis);

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(valueOps);
  }

  @Test
  void put_writesLocalAndRedisWithPrefixAndTtl() throws Exception {
    PermissionSnapshot snapshot = snapshot(1L, 2L, 3L, 4L, 5);

    cache.put("key", snapshot);

    assertNotNull(cache.getIfPresent("key"));
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(valueOps).set(eq("permission:snapshot:key"), captor.capture(), eq(Duration.ofMinutes(5)));
    assertEquals(snapshot, objectMapper.readValue(captor.getValue(), PermissionSnapshot.class));
  }

  @Test
  void getIfPresent_returnsLocalHitWithoutRedis() {
    PermissionSnapshot snapshot = snapshot(1L, 2L, 3L, 4L, 5);
    cache.put("key", snapshot);

    PermissionSnapshot result = cache.getIfPresent("key");

    assertEquals(snapshot, result);
    verify(valueOps, never()).get(anyString());
  }

  @Test
  void getIfPresent_returnsRedisHitAndWarmsLocal() throws Exception {
    PermissionSnapshot snapshot = snapshot(1L, 2L, 3L, 4L, 5);
    when(valueOps.get("permission:snapshot:key")).thenReturn(objectMapper.writeValueAsString(snapshot));

    PermissionSnapshot result = cache.getIfPresent("key");

    assertEquals(snapshot, result);
    // 第二次命中本地，不再访问 Redis。
    assertNotNull(cache.getIfPresent("key"));
    verify(valueOps, times(1)).get("permission:snapshot:key");
  }

  @Test
  void getIfPresent_returnsNullOnMiss() {
    when(valueOps.get("permission:snapshot:key")).thenReturn(null);

    assertNull(cache.getIfPresent("key"));
  }

  @Test
  void getIfPresent_returnsNullWhenRedisUnavailable() {
    when(valueOps.get("permission:snapshot:key")).thenThrow(new IllegalStateException("down"));

    assertNull(cache.getIfPresent("key"));
  }

  @Test
  void getIfPresent_returnsNullWhenJsonInvalid() {
    when(valueOps.get("permission:snapshot:key")).thenReturn("{not-json");

    assertNull(cache.getIfPresent("key"));
  }

  @Test
  void evict_removesLocalAndRedis() {
    cache.put("key", snapshot(1L, 2L, 3L, 4L, 5));

    cache.evict("key");

    assertNull(cache.getIfPresent("key"));
    verify(redis).delete("permission:snapshot:key");
  }

  @Test
  void evictAll_clearsLocalCache() {
    cache.put("a", snapshot(1L, 2L, 3L, 4L, 5));
    cache.put("b", snapshot(1L, 2L, 3L, 5L, 6));

    cache.evictAll();

    assertNull(cache.getIfPresent("a"));
    assertNull(cache.getIfPresent("b"));
  }

  private static PermissionSnapshot snapshot(long accountId, long tenantId, Long orgId, Long storeId, int version) {
    return new PermissionSnapshot(accountId, tenantId, orgId, storeId, version, List.of("perm.read", "perm.write"));
  }
}
