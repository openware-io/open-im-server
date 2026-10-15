package io.openware.platform.tenant.infra.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.platform.tenant.domain.authorization.PermissionSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;

/**
 * IAM 权限快照缓存：Redis 一级缓存（本地 ConcurrentHashMap 兜底）。
 * 授权变更事件逐出当前为全量 clear；生产按 accountId 前缀精确逐出 + MQ 事件。
 */
@Component
@Slf4j
public class PermissionSnapshotCache {

    private static final String KEY_PREFIX = "permission:snapshot:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, LocalEntry> local = new ConcurrentHashMap<>();

    public PermissionSnapshotCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void put(String key, PermissionSnapshot value) {
        local.put(key, new LocalEntry(value, System.nanoTime() + TTL.toNanos()));
        try {
            redis.opsForValue().set(KEY_PREFIX + key, objectMapper.writeValueAsString(value), TTL);
        // degradation: redis unavailable degrades to the local cache, which is the designed two-tier behaviour
        } catch (Exception ignored) { /* redis 不可用时依赖本地缓存 */ }
    }

    public PermissionSnapshot getIfPresent(String key) {
        LocalEntry cached = local.get(key);
        if (cached != null && cached.expiresAtNanos() > System.nanoTime()) {
            return cached.snapshot();
        } else if (cached != null) {
            local.remove(key, cached);
        }
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + key);
            if (json != null) {
                PermissionSnapshot snapshot = objectMapper.readValue(json, PermissionSnapshot.class);
                local.put(key, new LocalEntry(snapshot, System.nanoTime() + TTL.toNanos()));
                return snapshot;
            }
        // degradation: unreadable cached snapshot is treated as a miss and reloaded
        } catch (Exception ignored) { /* 反序列化失败视为未命中 */ }
        return null;
    }

    public void evict(String key) {
        local.remove(key);
        try {
            redis.delete(KEY_PREFIX + key);
        } catch (Exception ignored) {
            // 本地缓存已逐出，redis 逐出失败只影响其他副本的失效时机，属降级但仍需可追溯。
            log.debug("failed to evict permission snapshot from redis for key {}", key, ignored);
        }
    }

    public void evictByPrefix(String prefix) {
        local.keySet().removeIf(key -> key.startsWith(prefix));
        try {
            Set<String> keys = scan(KEY_PREFIX + prefix + "*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        // degradation: redis unavailable degrades to the local cache, which is the designed two-tier behaviour
        } catch (Exception ignored) { /* redis 不可用时依赖本地缓存 */ }
    }

    public void evictAll() {
        local.clear();
        try {
            Set<String> keys = scan(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        // degradation: redis unavailable degrades to clearing the local cache only, by contract
        } catch (Exception ignored) { /* redis 不可用时仅清本地缓存 */ }
    }

    private Set<String> scan(String pattern) {
        Set<String> keys = new HashSet<>();
        redis.execute(connection -> {
            try (Cursor<byte[]> cursor = connection.scan(ScanOptions.scanOptions().match(pattern).count(200).build())) {
                cursor.forEachRemaining(value -> keys.add(new String(value, StandardCharsets.UTF_8)));
            }
            return null;
        }, true);
        return keys;
    }

    private record LocalEntry(PermissionSnapshot snapshot, long expiresAtNanos) {}
        }