package io.openware.platform.tenant.infra.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.platform.tenant.domain.iam.model.PermissionCatalogSnapshot;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * IAM 权限目录矩阵缓存：Redis 一级 + 本地兜底。
 *
 * <p>缓存键包含数据库派生的目录版本：版本不变则命中，任一门禁/角色/授权写入改变版本后自然落到
 * 新键，旧键按 TTL 过期。这样多实例部署下无需广播失效消息，也不会出现“一台机器刷新了、另一台还
 * 展示旧矩阵”的不一致。
 *
 * <p>Redis 不可用时退化为本地缓存；本地缓存同样按版本键控，因此不会返回旧版本数据，只是失去跨实例
 * 命中率。
 */
@Component
@Slf4j
public class PermissionCatalogCache {

  private static final String KEY_PREFIX = "iam:catalog:matrix:";
  private static final Duration TTL = Duration.ofMinutes(10);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<String, LocalEntry> local = new ConcurrentHashMap<>();

  public PermissionCatalogCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** 按目录版本读取快照；未命中返回 {@code null}。 */
  public PermissionCatalogSnapshot get(String version) {
    LocalEntry cached = local.get(version);
    if (cached != null && cached.expiresAtNanos() > System.nanoTime()) {
      return cached.snapshot();
    }
    if (cached != null) {
      local.remove(version, cached);
    }
    try {
      String json = redis.opsForValue().get(KEY_PREFIX + version);
      if (json != null) {
        PermissionCatalogSnapshot snapshot = objectMapper.readValue(json, PermissionCatalogSnapshot.class);
        local.put(version, new LocalEntry(snapshot, System.nanoTime() + TTL.toNanos()));
        return snapshot;
      }
    // degradation: unreadable cached snapshot is treated as a miss and rebuilt
    } catch (Exception ignored) { /* 反序列化失败视为未命中 */ }
    return null;
  }

  /** 写入快照；写失败只影响后续命中率，不影响本次响应。 */
  public void put(String version, PermissionCatalogSnapshot snapshot) {
    local.put(version, new LocalEntry(snapshot, System.nanoTime() + TTL.toNanos()));
    try {
      redis.opsForValue().set(KEY_PREFIX + version, objectMapper.writeValueAsString(snapshot), TTL);
    // degradation: redis unavailable degrades to the local cache, which is the designed two-tier behaviour
    } catch (Exception exception) {
      log.debug("failed to cache permission catalog for version {}", version, exception);
    }
  }

  private record LocalEntry(PermissionCatalogSnapshot snapshot, long expiresAtNanos) {}
}
