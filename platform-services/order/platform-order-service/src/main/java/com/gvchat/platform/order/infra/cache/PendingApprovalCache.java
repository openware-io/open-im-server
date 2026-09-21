package com.gvchat.platform.order.infra.cache;

import com.gvchat.platform.order.application.dto.PendingApprovalView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「客户待确认加项」聚合读缓存（本地进程内、带 TTL）。
 *
 * <p><b>为什么缓存</b>：后台角标 / 收银台卡片标记 / 订单管理列表 / App 横幅 / B 端卡片角标都要读同一份
 * 「本门店待确认加项」，前端为了「实时」会 10–30s 轮询一次；不缓存的话每次轮询都要扫明细 + 回查订单，
 * 门店高峰期（几十张单）会把读放大成每秒上百次查询。
 *
 * <p><b>缓存 key</b>：{@code ord:pending-approval:{tenantId}:{storeId}}（维度与查询口径一致，跨门店不串味）。
 *
 * <p><b>失效时机（写后失效）</b>：客户提交加项、运营确认/拒绝加项，都在**同一事务提交后**调用
 * {@link #invalidate}；因此本实例的角标在写操作返回后**立即**反映新状态，不依赖 TTl 到期。
 *
 * <p><b>多实例一致性</b>：本地缓存无法广播失效，其它实例最坏多看到 TTL 时长的旧计数（默认 3s，
 * {@code platform.order.pending-approval.cache-ttl-ms}）。这个旧值只影响「角标数字」，不影响任何业务判定：
 * 确认/拒绝走的是数据库条件更新（见 {@code OrderItemMapper.markApproval}），永不读缓存。
 * 将来接入消息中心后，{@link #invalidate} 的位置就是「订阅加项事件」的落点，本类可整体替换为 Redis/消息驱动。
 */
@Component
@Slf4j
public class PendingApprovalCache {

    /** 单一 key 前缀，便于日志与后续替换成 Redis 实现。 */
    private static final String KEY_PREFIX = "ord:pending-approval:";
    /** 本地缓存条目上限：超过即整体清空（读路径幂等回源，清空无副作用）。 */
    private static final int MAX_ENTRIES = 500;

    private final long ttlMillis;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public PendingApprovalCache(
            @Value("${platform.order.pending-approval.cache-ttl-ms:3000}") long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** 缓存 key（租户 + 门店）。 */
    public static String key(Long tenantId, Long storeId) {
        return KEY_PREFIX + tenantId + ":" + storeId;
    }

    /** 是否启用缓存（TTL <= 0 视为关闭，全部回源，便于排障）。 */
    public boolean enabled() {
        return ttlMillis > 0;
    }

    /** 读缓存：未命中或已过期返回 empty（调用方回源）。 */
    public Optional<PendingApprovalView> get(Long tenantId, Long storeId) {
        if (!enabled() || tenantId == null || storeId == null) {
            return Optional.empty();
        }
        Entry entry = entries.get(key(tenantId, storeId));
        if (entry == null || entry.expiresAtMillis() <= System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(entry.view());
    }

    /** 写缓存（回源后回填）。 */
    public void put(Long tenantId, Long storeId, PendingApprovalView view) {
        if (!enabled() || tenantId == null || storeId == null || view == null) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
            log.debug("待确认加项本地缓存达到 {} 条上限，已清空回源", MAX_ENTRIES);
        }
        entries.put(key(tenantId, storeId), new Entry(view, System.currentTimeMillis() + ttlMillis));
    }

    /** 写路径后失效本门店（客户提交加项 / 运营确认 / 拒绝）。 */
    public void invalidate(Long tenantId, Long storeId) {
        if (tenantId == null || storeId == null) {
            return;
        }
        entries.remove(key(tenantId, storeId));
    }

    /** 失效全部（批量修正/数据修复后使用）。 */
    public void invalidateAll() {
        entries.clear();
    }

    /** 缓存条目（值 + 过期时间戳）。 */
    private record Entry(PendingApprovalView view, long expiresAtMillis) {}
}
