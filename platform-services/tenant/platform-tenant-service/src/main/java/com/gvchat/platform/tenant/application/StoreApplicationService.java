package com.gvchat.platform.tenant.application;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.time.StoreTimeService;
import com.gvchat.platform.tenant.infra.persistence.mapper.StoreMapper;
import com.gvchat.platform.tenant.infra.persistence.po.StorePo;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 门店配置写路径（多时区批 1：门店时区 + 营业日切点）。
 *
 * <p>依据 `docs/renovation/MULTI_TIMEZONE_DESIGN.md` §2.1（时区归属层级、门店级为权威来源、写路径 fail-closed）
 * 与 §3.1（切点默认 `04:00`、允许范围 `00:00`–`12:00`）。
 *
 * <p>边界与失败口径：
 * <ul>
 *   <li><b>租户边界</b>：门店必须属于签名上下文的租户，否则 403 {@code TENANT_SCOPE_DENIED}；
 *       用 {@link StoreMapper#selectTenantIdById(Long)}（忽略租户拦截）区分「不存在」与「跨租户」，
 *       不让越权访问被静默降级成 404；</li>
 *   <li><b>门店边界</b>：签名上下文带门店时，路径门店必须是同一门店，否则 403 {@code STORE_SCOPE_DENIED}；</li>
 *   <li><b>校验</b>：时区与切点一律由 {@link StoreTimeService} 校验（非法 400，且不产生任何写入），
 *       落库前先把值规范化，避免「07:00」「7:00」「07:00:30」三种写法在库里并存；</li>
 *   <li><b>不重算历史</b>：改时区/切点只影响变更之后新产生的记录（文档 §2.1、§3.2），
 *       历史换算不在本类的职责内。</li>
 * </ul>
 */
@Service
public class StoreApplicationService {

    /** 错误码：请求体没有任何可修改字段。 */
    public static final String CODE_STORE_UPDATE_EMPTY = "STORE_UPDATE_EMPTY";

    /** 错误码：门店不存在。 */
    public static final String CODE_STORE_NOT_FOUND = "STORE_NOT_FOUND";

    /** 错误码：签名上下文的门店与目标门店不一致。 */
    public static final String CODE_STORE_SCOPE_DENIED = "STORE_SCOPE_DENIED";

    /** 错误码：门店不属于签名上下文的租户。 */
    public static final String CODE_TENANT_SCOPE_DENIED = "TENANT_SCOPE_DENIED";

    private final StoreMapper storeMapper;

    public StoreApplicationService(StoreMapper storeMapper) {
        this.storeMapper = storeMapper;
    }

    /**
     * 修改门店时区与营业日切点：字段级可选，未传的字段保持原值。
     *
     * @param tenantId           已由签名上下文收敛的租户
     * @param contextStoreId     签名上下文里的门店（可为 {@code null} = 租户/组织级上下文）
     * @param storeId            路径上的门店主键
     * @param operatorId         操作人（写入 {@code updated_by} 并进审计）
     * @param timezone           IANA 时区 id，{@code null} 表示不修改
     * @param businessDayCutoff  `HH:mm` / `HH:mm:ss`，{@code null} 表示不修改
     * @return 变更后的门店与前后值（供审计 detail 与响应）
     */
    @Transactional
    public StoreScheduleResult updateSchedule(long tenantId, Long contextStoreId, Long storeId, Long operatorId,
                                              String timezone, String businessDayCutoff) {
        if (timezone == null && businessDayCutoff == null) {
            throw new ApiException(400, CODE_STORE_UPDATE_EMPTY,
                    "没有可修改的字段：请至少传 timezone 或 businessDayCutoff");
        }

        // 校验先于查库与写入：入参非法一律 400，既不做任何 UPDATE，也不为坏请求触达数据库。
        ZoneId zone = timezone == null ? null : StoreTimeService.requireZoneId(timezone);
        LocalTime cutoff = businessDayCutoff == null ? null : StoreTimeService.requireBusinessDayCutoff(businessDayCutoff);

        StorePo store = requireScopedStore(tenantId, contextStoreId, storeId);

        String timezoneBefore = store.getTimezone();
        String cutoffBefore = store.getBusinessDayCutoff();

        // 落库文本规范化：时区用 ZoneId 的规范 id；切点对齐 MySQL TIME 列的 HH:mm:ss（与列表出参一致）。
        String timezoneAfter = zone == null ? timezoneBefore : zone.getId();
        String cutoffAfter = cutoff == null ? cutoffBefore : StoreTimeService.formatBusinessDayCutoff(cutoff) + ":00";

        StorePo patch = new StorePo();
        patch.setId(storeId);
        if (zone != null) {
            patch.setTimezone(timezoneAfter);
        }
        if (cutoff != null) {
            patch.setBusinessDayCutoff(cutoffAfter);
        }
        patch.setUpdatedBy(operatorId);
        LocalDateTime updatedAt = LocalDateTime.now();
        patch.setUpdatedAt(updatedAt);
        storeMapper.updateById(patch);

        // 同一个事务内把变更回填到已加载实体，避免为了出参再查一次（也保证响应与落库值一致）。
        store.setTimezone(timezoneAfter);
        store.setBusinessDayCutoff(cutoffAfter);
        store.setUpdatedBy(operatorId);
        store.setUpdatedAt(updatedAt);

        return new StoreScheduleResult(store, timezoneBefore, timezoneAfter, cutoffBefore, cutoffAfter);
    }

    /**
     * 按签名上下文加载门店：跨门店 → 403，跨租户 → 403，不存在 → 404。
     *
     * <p>先判门店边界再查库：门店上下文是最便宜的一次比较，越权请求不必触达数据库。
     */
    public StorePo requireScopedStore(long tenantId, Long contextStoreId, Long storeId) {
        if (contextStoreId != null && !contextStoreId.equals(storeId)) {
            throw new ApiException(403, CODE_STORE_SCOPE_DENIED, "只能修改当前上下文门店的门店配置");
        }
        Long ownerTenantId = storeMapper.selectTenantIdById(storeId);
        if (ownerTenantId == null) {
            throw new ApiException(404, CODE_STORE_NOT_FOUND, "门店不存在: " + storeId);
        }
        if (ownerTenantId != tenantId) {
            throw new ApiException(403, CODE_TENANT_SCOPE_DENIED, "只能修改当前上下文租户的门店配置");
        }
        StorePo store = storeMapper.selectById(storeId);
        if (store == null) {
            throw new ApiException(404, CODE_STORE_NOT_FOUND, "门店不存在: " + storeId);
        }
        return store;
    }

    /**
     * 门店时区/切点变更结果。
     *
     * @param store          变更后的门店（已回填新值，直接作为响应体）
     * @param timezoneBefore 变更前门店时区
     * @param timezoneAfter  变更后门店时区
     * @param cutoffBefore   变更前营业日切点（DB 的 `HH:mm:ss` 文本）
     * @param cutoffAfter    变更后营业日切点（DB 的 `HH:mm:ss` 文本）
     */
    public record StoreScheduleResult(StorePo store, String timezoneBefore, String timezoneAfter,
                                      String cutoffBefore, String cutoffAfter) {
    }
}
