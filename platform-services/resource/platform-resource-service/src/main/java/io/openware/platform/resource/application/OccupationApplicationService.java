package io.openware.platform.resource.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.platform.resource.infra.persistence.mapper.EventOutboxMapper;
import io.openware.platform.resource.infra.persistence.mapper.OccupationMapper;
import io.openware.platform.resource.infra.persistence.po.OccupationPo;
import io.openware.platform.resource.infra.persistence.po.ResEventOutboxPo;
import io.openware.protocol.mq.event.ResourceEventTypes;
import io.openware.protocol.mq.event.ResourceOccupationCancelledEvent;
import io.openware.protocol.mq.event.ResourceOccupationReleasedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 资源占用应用服务。HELD 占用经 hold_expires_at 超时后由 HoldExpiryScheduler 触发状态机 HELD→RELEASED
 * （乐观锁 version CAS）；显式释放/取消与业务同事务写入 Outbox，由 EventOutboxRelay 补偿投递。
 */
@Service
public class OccupationApplicationService {
    private static final String STATUS_RELEASED = "RELEASED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final int DEFAULT_HOLD_TIMEOUT_MINUTES = 15;
    private static final int EXPIRY_BATCH_SIZE = 500;

    private final OccupationMapper occupationMapper;
    private final EventOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public OccupationApplicationService(OccupationMapper occupationMapper, EventOutboxMapper outboxMapper) {
        this.occupationMapper = occupationMapper;
        this.outboxMapper = outboxMapper;
    }

    /**
     * 占用资源（HELD）：同一资源有效时段半开区间 [start,end) 重叠则拒绝。
     * DB 锁 + 半开区间判重是最终事实；Redis 锁仅降冲突（此处不引入）。
     */
    @Transactional
    public OccupationPo holdResource(Long tenantId, Long storeId, Long resourceId,
                                     LocalDateTime startAt, LocalDateTime endAt,
                                     String sourceType, Long sourceId, LocalDateTime holdExpiresAt) {
        var valids = occupationMapper.selectValidForUpdate(tenantId, resourceId);
        for (OccupationPo o : valids) {
            // 半开区间 [start,end)：重叠当且仅当 a.start < b.end && b.start < a.end
            boolean overlap = o.getStartAt().isBefore(endAt) && startAt.isBefore(o.getEndAt());
            if (overlap) {
                throw new IllegalStateException("RESOURCE_OCCUPIED");
            }
        }
        LocalDateTime effectiveHoldExpiresAt = holdExpiresAt != null
                ? holdExpiresAt : LocalDateTime.now().plusMinutes(DEFAULT_HOLD_TIMEOUT_MINUTES);
        OccupationPo po = new OccupationPo();
        po.setTenantId(tenantId);
        po.setStoreId(storeId);
        po.setResourceId(resourceId);
        po.setSourceType(sourceType);
        po.setSourceId(sourceId);
        po.setStartAt(startAt);
        po.setEndAt(endAt);
        po.setStatus("HELD");
        po.setHoldExpiresAt(effectiveHoldExpiresAt);
        po.setVersion(0);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        occupationMapper.insert(po);
        return po;
    }

    /**
     * 显式释放：HELD/RESERVED/IN_USE → RELEASED（乐观锁 version CAS），并落 Outbox。
     *
     * <p>释放时刻 {@code now} 显式传给 SQL 的 {@code updatedAt} 参数，SQL 侧把 {@code end_at} 收缩到该时刻；
     * 内存态同步收缩，保证返回给调用方的占用与 Outbox 事件里的窗口和落库行一致。
     */
    @Transactional
    public OccupationPo releaseOccupation(Long occupationId) {
        OccupationPo po = require(occupationId);
        if (STATUS_RELEASED.equals(po.getStatus())) {
            return po;
        }
        LocalDateTime now = LocalDateTime.now();
        if (occupationMapper.releaseWithVersion(po.getId(), versionOf(po), now) == 0) {
            throw new IllegalStateException("OCCUPATION_VERSION_CONFLICT");
        }
        po.setStatus(STATUS_RELEASED);
        shrinkEndAt(po, now);
        po.setUpdatedAt(now);
        writeReleasedOutbox(po, now);
        return po;
    }

    /** 显式取消：HELD/RESERVED → CANCELLED（乐观锁 version CAS），并落 Outbox。 */
    @Transactional
    public OccupationPo cancelOccupation(Long occupationId) {
        OccupationPo po = require(occupationId);
        if (STATUS_CANCELLED.equals(po.getStatus())) {
            return po;
        }
        LocalDateTime now = LocalDateTime.now();
        if (occupationMapper.cancelWithVersion(po.getId(), versionOf(po), now) == 0) {
            throw new IllegalStateException("OCCUPATION_VERSION_CONFLICT");
        }
        po.setStatus(STATUS_CANCELLED);
        po.setUpdatedAt(now);
        writeCancelledOutbox(po, now);
        return po;
    }

    /**
     * 过期释放：扫描已过期 HELD 并逐个 CAS 释放为 RELEASED，成功者落 Outbox，返回释放行数。
     *
     * <p>释放时刻用扫描入参 {@code now}（由定时任务显式传入），不用 SQL 的 {@code NOW()}，保证可测；
     * 窗口同样收缩到该时刻：预占超时的 HELD 是「没被确认的占位」，不能继续占着 24 小时窗口。
     */
    @Transactional
    public int releaseExpiredHolds(LocalDateTime now) {
        List<OccupationPo> expired = occupationMapper.selectExpiredHolds(now, EXPIRY_BATCH_SIZE);
        int released = 0;
        for (OccupationPo po : expired) {
            if (occupationMapper.releaseWithVersion(po.getId(), versionOf(po), now) == 1) {
                po.setStatus(STATUS_RELEASED);
                shrinkEndAt(po, now);
                po.setUpdatedAt(now);
                writeReleasedOutbox(po, now);
                released++;
            }
        }
        return released;
    }

    /**
     * 兜底释放：扫描**业务时段已结束**（{@code end_at < now}）但仍有效的占用并 CAS 释放为 RELEASED。
     *
     * <p>为什么需要：结台/取消/转台都会显式释放占用，但释放调用是「失败只记录」的降级路径
     * （资源服务抖动、进程中断），一旦漏掉，房态会一直显示「使用中」：包厢不能清洁完成、不能再开台、
     * 也不能被预约分配，只能人工改库。这里按业务窗口 {@code end_at} 做最终兜底——
     * 开台占用写的是 {@code openedAt + 24h}，正常营业中的会话不会被误释放。
     */
    @Transactional
    public int releaseEndedOccupations(LocalDateTime now) {
        List<OccupationPo> ended = occupationMapper.selectEndedOccupations(now, EXPIRY_BATCH_SIZE);
        int released = 0;
        for (OccupationPo po : ended) {
            if (occupationMapper.releaseWithVersion(po.getId(), versionOf(po), now) == 1) {
                po.setStatus(STATUS_RELEASED);
                // 该路径扫描条件即 end_at < now，收缩是无操作：SQL 的 LEAST 守护「已更早的 end_at 不被拉长」
                shrinkEndAt(po, now);
                po.setUpdatedAt(now);
                writeReleasedOutbox(po, now);
                released++;
            }
        }
        return released;
    }

    /**
     * 释放时收缩占用窗口：与 {@code releaseWithVersion} 的 SQL
     * （{@code GREATEST(start_at, LEAST(COALESCE(end_at, updatedAt), updatedAt))}）同规则，
     * 只允许提前、不允许延后，且不早于 {@code start_at}；{@code end_at} 为 NULL 时取释放时刻。
     * 内存态同步收缩是为了让返回给调用方的占用、Outbox 事件的 {@code endAt} 与落库行一致，
     * 否则读事件的下游仍会把一次开台读成 24 小时（或在未开始的时段上读到倒挂区间）。
     */
    private static void shrinkEndAt(OccupationPo po, LocalDateTime now) {
        LocalDateTime shrunk = po.getEndAt() == null || po.getEndAt().isAfter(now) ? now : po.getEndAt();
        if (po.getStartAt() != null && shrunk.isBefore(po.getStartAt())) {
            // 占用还没开始就被释放（如 holdExpiresAt 早于 start_at 的未来时段 HELD 超时）：最坏零长度
            shrunk = po.getStartAt();
        }
        po.setEndAt(shrunk);
    }

    private OccupationPo require(Long occupationId) {
        OccupationPo po = occupationMapper.selectById(occupationId);
        if (po == null) {
            throw new IllegalStateException("OCCUPATION_NOT_FOUND");
        }
        return po;
    }

    private int versionOf(OccupationPo po) {
        return po.getVersion() == null ? 0 : po.getVersion();
    }

    private void writeReleasedOutbox(OccupationPo po, LocalDateTime now) {
        ResourceOccupationReleasedEvent event = ResourceOccupationReleasedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .tenantId(po.getTenantId())
                .storeId(po.getStoreId())
                .resourceId(po.getResourceId())
                .occupationId(po.getId())
                .sourceType(po.getSourceType())
                .sourceId(po.getSourceId())
                .startAt(toInstant(po.getStartAt()))
                .endAt(toInstant(po.getEndAt()))
                .status(STATUS_RELEASED)
                .occurredAt(now.toInstant(ZoneOffset.UTC))
                .build();
        writeOutbox(event.getEventId(), ResourceEventTypes.RESOURCE_OCCUPATION_RELEASED, event, po.getId(), now);
    }

    private void writeCancelledOutbox(OccupationPo po, LocalDateTime now) {
        ResourceOccupationCancelledEvent event = ResourceOccupationCancelledEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .tenantId(po.getTenantId())
                .storeId(po.getStoreId())
                .resourceId(po.getResourceId())
                .occupationId(po.getId())
                .sourceType(po.getSourceType())
                .sourceId(po.getSourceId())
                .startAt(toInstant(po.getStartAt()))
                .endAt(toInstant(po.getEndAt()))
                .status(STATUS_CANCELLED)
                .occurredAt(now.toInstant(ZoneOffset.UTC))
                .build();
        writeOutbox(event.getEventId(), ResourceEventTypes.RESOURCE_OCCUPATION_CANCELLED, event, po.getId(), now);
    }

    private void writeOutbox(String eventId, String eventType, Object event, Long aggregateId, LocalDateTime now) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("OUTBOX_SERIALIZE_FAILED", e);
        }
        ResEventOutboxPo outbox = new ResEventOutboxPo();
        outbox.setEventId(eventId);
        outbox.setEventType(eventType);
        outbox.setAggregateType("occupation");
        outbox.setAggregateId(String.valueOf(aggregateId));
        outbox.setPayloadJson(payload);
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
