package io.openware.platform.order.infra.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库存可用量读缓存（本地进程内、带 TTL）：点单列表 / 售罄判断是高频读，每次回源会放大成 N 次查询。
 *
 * <p><b>缓存 key</b>：{@code ord:inv:avail:{tenantId}:{storeId}:{materialId}}，值为「可用库存 on_hand − reserved」。
 * 维度与库存行唯一键 (tenant_id, store_id, material_id) 一致，跨门店/跨租户不会串味。
 *
 * <p><b>失效时机</b>：任何库存写路径（入库/调整/加项扣减/作废回补）在事务内调用 {@link #invalidate}；
 * 商品/物料上下架不影响可用量，不需要失效。
 *
 * <p><b>与多实例</b>：本地缓存无法广播失效，因此 TTL 必须短（默认 2s，见
 * {@code platform.order.inventory.availability-cache-ttl-ms}），其他实例最坏多看到 TTL 时长的旧值。
 * 这个旧值只影响「能不能点」的展示，不影响正确性。
 *
 * <p><b>降级与防超卖</b>：缓存不可用（未启用/未命中/已过期）一律回源数据库；扣减永远走数据库条件更新，
 * 绝不读缓存做扣减，因此缓存再脏也不会把库存扣成负数或放行超卖。
 */
@Component
@Slf4j
public class InventoryAvailabilityCache {

    /** 单一 key 前缀，便于日志与后续替换成 Redis 实现。 */
    private static final String KEY_PREFIX = "ord:inv:avail:";
    /** 本地缓存条目上限：超过即整体清空，避免热点物料把内存撑爆（库存读是幂等回源，清空无副作用）。 */
    private static final int MAX_ENTRIES = 10_000;

    private final long ttlMillis;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public InventoryAvailabilityCache(
            @Value("${platform.order.inventory.availability-cache-ttl-ms:2000}") long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** 缓存 key（租户 + 门店 + 物料）。 */
    public static String key(Long tenantId, Long storeId, Long materialId) {
        return KEY_PREFIX + tenantId + ":" + storeId + ":" + materialId;
    }

    /** 是否启用缓存（TTL <= 0 视为关闭，全部回源，便于排障）。 */
    public boolean enabled() {
        return ttlMillis > 0;
    }

    /** 读缓存：未命中或已过期返回 empty（调用方回源）。 */
    public Optional<BigDecimal> get(Long tenantId, Long storeId, Long materialId) {
        if (!enabled()) {
            return Optional.empty();
        }
        Entry entry = entries.get(key(tenantId, storeId, materialId));
        if (entry == null || entry.expiresAtMillis() <= System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(entry.availableQty());
    }

    /** 写缓存（回源后回填）。 */
    public void put(Long tenantId, Long storeId, Long materialId, BigDecimal availableQty) {
        if (!enabled()) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
            log.debug("库存可用量本地缓存达到 {} 条上限，已清空回源", MAX_ENTRIES);
        }
        entries.put(key(tenantId, storeId, materialId),
                new Entry(availableQty == null ? BigDecimal.ZERO : availableQty,
                        System.currentTimeMillis() + ttlMillis));
    }

    /** 库存写路径后失效单个物料（写后失效：本实例立即回源，其他实例最坏等一个 TTL）。 */
    public void invalidate(Long tenantId, Long storeId, Long materialId) {
        if (materialId == null) {
            return;
        }
        entries.remove(key(tenantId, storeId, materialId));
    }

    /** 失效全部（批量修正/数据修复后使用）。 */
    public void invalidateAll() {
        entries.clear();
    }

    /** 缓存条目（值 + 过期时间戳）。 */
    private record Entry(BigDecimal availableQty, long expiresAtMillis) {}
}
