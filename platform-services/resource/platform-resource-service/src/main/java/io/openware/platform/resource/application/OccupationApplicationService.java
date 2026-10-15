package io.openware.platform.resource.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.platform.resource.domain.occupation.model.Occupation;
import io.openware.platform.resource.domain.occupation.repository.OccupationEventOutbox;
import io.openware.platform.resource.domain.occupation.repository.OccupationRepository;
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
 *
 * <p>持久化经领域端口：行锁（{@code FOR UPDATE}）、乐观锁条件更新与窗口单向收缩都在适配器/ mapper 的
 * SQL 里完成，应用层只做编排。状态迁移一律走端口的 CAS 方法，**不得**改成先读后判再写。
 */
@Service
public class OccupationApplicationService {
    private static final int DEFAULT_HOLD_TIMEOUT_MINUTES = 15;
    private static final int EXPIRY_BATCH_SIZE = 500;

    private final OccupationRepository occupationRepository;
    private final OccupationEventOutbox outbox;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public OccupationApplicationService(OccupationRepository occupationRepository,
                                        OccupationEventOutbox outbox) {
        this.occupationRepository = occupationRepository;
        this.outbox = outbox;
    }

    /**
     * 占用资源（HELD）：同一资源有效时段半开区间 [start,end) 重叠则拒绝。
     * DB 锁 + 半开区间判重是最终事实；Redis 锁仅降冲突（此处不引入）。
     */
    @Transactional
    public Occupation holdResource(Long tenantId, Long storeId, Long resourceId,
                                   LocalDateTime startAt, LocalDateTime endAt,
                                   String sourceType, Long sourceId, LocalDateTime holdExpiresAt) {
        // 行锁读（FOR UPDATE）：锁持有至事务提交，这是并发预占不会双双通过重叠检查的唯一保证。
        var valids = occupationRepository.findValidForUpdate(tenantId, resourceId);
        for (Occupation o : valids) {
            // 半开区间 [start,end)：相邻窗口不算重叠。判据收在领域模型上，避免各处各写一遍比较式。
            if (o.overlaps(startAt, endAt)) {
                throw new IllegalStateException("RESOURCE_OCCUPIED");
            }
        }
        LocalDateTime effectiveHoldExpiresAt = holdExpiresAt != null
                ? holdExpiresAt : LocalDateTime.now().plusMinutes(DEFAULT_HOLD_TIMEOUT_MINUTES);
        // 初始状态与版本由领域工厂固定（预占的初始状态是用例不变量）。
        return occupationRepository.insert(Occupation.hold(tenantId, storeId, resourceId, sourceType,
                sourceId, startAt, endAt, effectiveHoldExpiresAt));
    }

    /**
     * 显式释放：HELD/RESERVED/IN_USE → RELEASED（乐观锁 version CAS），并落 Outbox。
     *
     * <p>释放时刻 {@code now} 显式传给 SQL 的 {@code updatedAt} 参数，SQL 侧把 {@code end_at} 收缩到该时刻；
     * 内存态同步收缩，保证返回给调用方的占用与 Outbox 事件里的窗口和落库行一致。
     */
    @Transactional
    public Occupation releaseOccupation(Long occupationId) {
        Occupation po = require(occupationId);
        if (po.released()) {
            return po;
        }
        LocalDateTime now = LocalDateTime.now();
        // 单条条件更新（状态 + 版本 CAS）并同时把窗口收缩到 now，见端口契约；不得改成先读后判再写。
        if (!occupationRepository.releaseWithVersion(po.id(), po.currentVersion(), now)) {
            throw new IllegalStateException("OCCUPATION_VERSION_CONFLICT");
        }
        Occupation released = releasedSnapshot(po, now);
        writeReleasedOutbox(released, now);
        return released;
    }

    /** 显式取消：HELD/RESERVED → CANCELLED（乐观锁 version CAS），并落 Outbox。 */
    @Transactional
    public Occupation cancelOccupation(Long occupationId) {
        Occupation po = require(occupationId);
        if (po.cancelled()) {
            return po;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!occupationRepository.cancelWithVersion(po.id(), po.currentVersion(), now)) {
            throw new IllegalStateException("OCCUPATION_VERSION_CONFLICT");
        }
        Occupation cancelled = withStatus(po, Occupation.STATUS_CANCELLED, po.endAt());
        writeCancelledOutbox(cancelled, now);
        return cancelled;
    }

    /**
     * 过期释放：扫描已过期 HELD 并逐个 CAS 释放为 RELEASED，成功者落 Outbox，返回释放行数。
     *
     * <p>释放时刻用扫描入参 {@code now}（由定时任务显式传入），不用 SQL 的 {@code NOW()}，保证可测；
     * 窗口同样收缩到该时刻：预占超时的 HELD 是「没被确认的占位」，不能继续占着 24 小时窗口。
     */
    @Transactional
    public int releaseExpiredHolds(LocalDateTime now) {
        List<Occupation> expired = occupationRepository.findExpiredHolds(now, EXPIRY_BATCH_SIZE);
        int released = 0;
        for (Occupation po : expired) {
            if (occupationRepository.releaseWithVersion(po.id(), po.currentVersion(), now)) {
                writeReleasedOutbox(releasedSnapshot(po, now), now);
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
        List<Occupation> ended = occupationRepository.findEndedOccupations(now, EXPIRY_BATCH_SIZE);
        int released = 0;
        for (Occupation po : ended) {
            if (occupationRepository.releaseWithVersion(po.id(), po.currentVersion(), now)) {
                // 该路径扫描条件即 end_at < now，收缩是无操作：SQL 的 LEAST 守护「已更早的 end_at 不被拉长」
                writeReleasedOutbox(releasedSnapshot(po, now), now);
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
    /**
     * 释放后的内存态快照：状态置为 RELEASED，并把窗口终点收缩到 {@code now}。
     *
     * <p><b>落库的收缩由 mapper 的单条 SQL 完成</b>（{@code GREATEST(start_at, LEAST(COALESCE(end_at, now), now))}），
     * 那是权威实现；本方法只为**返回给调用方的对象与 Outbox 载荷**构造一致的窗口，规则与之对齐：
     * 已更早的 {@code end_at} 保持原值（不被反向拉长），且结果不早于 {@code start_at}（兜住"尚未开始就释放"
     * 可能产生的负长度区间）。
     */
    private static Occupation releasedSnapshot(Occupation po, LocalDateTime now) {
        LocalDateTime shrunk = po.endAt() == null || po.endAt().isAfter(now) ? now : po.endAt();
        if (po.startAt() != null && shrunk.isBefore(po.startAt())) {
            shrunk = po.startAt();
        }
        return withStatus(po, Occupation.STATUS_RELEASED, shrunk);
    }

    /** 状态/窗口替换：不可变聚合按需生成新实例。 */
    private static Occupation withStatus(Occupation po, String status, LocalDateTime endAt) {
        return new Occupation(po.id(), po.tenantId(), po.storeId(), po.resourceId(), po.sourceType(),
                po.sourceId(), po.startAt(), endAt, status, po.holdExpiresAt(), po.version());
    }

    private Occupation require(Long occupationId) {
        Occupation po = occupationId == null ? null
                : occupationRepository.findById(occupationId).orElse(null);
        if (po == null) {
            throw new IllegalStateException("OCCUPATION_NOT_FOUND");
        }
        return po;
    }

    private void writeReleasedOutbox(Occupation po, LocalDateTime now) {
        ResourceOccupationReleasedEvent event = ResourceOccupationReleasedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .tenantId(po.tenantId())
                .storeId(po.storeId())
                .resourceId(po.resourceId())
                .occupationId(po.id())
                .sourceType(po.sourceType())
                .sourceId(po.sourceId())
                .startAt(toInstant(po.startAt()))
                .endAt(toInstant(po.endAt()))
                .status(Occupation.STATUS_RELEASED)
                .occurredAt(now.toInstant(ZoneOffset.UTC))
                .build();
        writeOutbox(event.getEventId(), ResourceEventTypes.RESOURCE_OCCUPATION_RELEASED, event, po.id());
    }

    private void writeCancelledOutbox(Occupation po, LocalDateTime now) {
        ResourceOccupationCancelledEvent event = ResourceOccupationCancelledEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .tenantId(po.tenantId())
                .storeId(po.storeId())
                .resourceId(po.resourceId())
                .occupationId(po.id())
                .sourceType(po.sourceType())
                .sourceId(po.sourceId())
                .startAt(toInstant(po.startAt()))
                .endAt(toInstant(po.endAt()))
                .status(Occupation.STATUS_CANCELLED)
                .occurredAt(now.toInstant(ZoneOffset.UTC))
                .build();
        writeOutbox(event.getEventId(), ResourceEventTypes.RESOURCE_OCCUPATION_CANCELLED, event, po.id());
    }

    private void writeOutbox(String eventId, String eventType, Object event, Long aggregateId) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("OUTBOX_SERIALIZE_FAILED", e);
        }
        // 出站记录的落库细节（含审计字段与 PENDING 状态）由适配器负责。
        outbox.append(eventId, eventType, String.valueOf(aggregateId), payload);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
