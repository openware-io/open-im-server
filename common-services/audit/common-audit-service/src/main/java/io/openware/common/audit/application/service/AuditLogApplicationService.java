package io.openware.common.audit.application.service;

import io.openware.common.audit.api.dto.AuditBatchWriteResult;
import io.openware.common.audit.api.dto.AuditWriteResult;
import io.openware.common.audit.application.command.AuditRecordCommand;
import io.openware.common.audit.domain.model.AuditLog;
import io.openware.common.audit.domain.repository.AuditLogRepository;
import io.openware.common.audit.infra.id.AuditIdGenerator;
import io.openware.common.exception.ApiException;
import io.openware.common.util.SnowflakeIdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计写入应用服务：接收各服务/BFF 的上报，做幂等落库。
 *
 * <p>幂等键口径（审计表唯一键 {@code (tenant_id, idempotency_key)}）：
 * <ol>
 *   <li>上报方显式给了 {@code idempotencyKey}（业务幂等键）→ 直接使用；</li>
 *   <li>给了 {@code requestId}/{@code traceId}（HTTP 请求级）→ 派生 {@code sha256(tenant|action|requestId|resource)}，
 *       同一请求被重复上报（网络重试、拦截器与领域服务双写）只留一条；</li>
 *   <li>都没有 → 用随机键，保证不误合并两次真实发生的相同操作。</li>
 * </ol>
 */
@Slf4j
@Service
public class AuditLogApplicationService {

    /** 单次批量上报条数上限。 */
    public static final int MAX_BATCH_SIZE = 200;

    private final AuditLogRepository repository;
    private final AuditIdGenerator idGenerator;

    @Autowired
    public AuditLogApplicationService(AuditLogRepository repository, AuditIdGenerator idGenerator) {
        this.repository = repository;
        this.idGenerator = idGenerator;
    }

    /** 兼容既有单测装配：不传 ID 生成器时用本地雪花生成器（生产装配始终注入 Spring Bean）。 */
    public AuditLogApplicationService(AuditLogRepository repository) {
        this(repository, new AuditIdGenerator(new SnowflakeIdGenerator()));
    }

    /** 写入单条审计记录（保持既有 {@code POST /internal/audit/records} 契约）。 */
    @Transactional
    public AuditWriteResult write(Map<String, Object> body) {
        return write(body, null);
    }

    /** 写入单条审计记录，并记录上报来源服务名（内部鉴权来源）。 */
    @Transactional
    public AuditWriteResult write(Map<String, Object> body, String sourceService) {
        return writeCommand(AuditRecordCommand.from(body), sourceService);
    }

    /** 写入已校验的命令（批量路径复用，避免重复解析）。 */
    @Transactional
    public AuditWriteResult writeCommand(AuditRecordCommand command, String sourceService) {
        AuditLog record = toLog(command, sourceService);
        AuditLogRepository.SaveResult result = repository.saveIfAbsent(record);
        if (result.duplicated()) {
            log.info("审计幂等命中，不再重复落库: action={}, idempotencyKey={}, id={}",
                    command.action(), record.getIdempotencyKey(), result.id());
        }
        return new AuditWriteResult(result.id(), result.duplicated());
    }

    /**
     * 批量写入：逐条校验通过才写（任一非法条目整批 400，避免半批写入），
     * 落库走 {@link AuditLogRepository#saveAllIfAbsent}——台账逐条抢占、主表一次多行插入。
     */
    @Transactional
    public AuditBatchWriteResult writeBatch(List<Map<String, Object>> bodies, String sourceService) {
        if (bodies == null || bodies.isEmpty()) {
            throw new ApiException(400, "INVALID_ARGUMENT", "records 不能为空");
        }
        if (bodies.size() > MAX_BATCH_SIZE) {
            throw new ApiException(400, "BATCH_TOO_LARGE",
                    "单次最多上报 " + MAX_BATCH_SIZE + " 条，实际 " + bodies.size());
        }
        List<AuditRecordCommand> commands = new ArrayList<>(bodies.size());
        for (int index = 0; index < bodies.size(); index++) {
            try {
                commands.add(AuditRecordCommand.from(bodies.get(index)));
            } catch (ApiException exception) {
                throw new ApiException(exception.getStatus(), exception.getCode(),
                        "records[" + index + "] " + exception.getMessage());
            }
        }
        List<AuditLog> records = new ArrayList<>(commands.size());
        for (AuditRecordCommand command : commands) {
            records.add(toLog(command, sourceService));
        }
        List<AuditLogRepository.SaveResult> saved = repository.saveAllIfAbsent(records);
        List<AuditWriteResult> results = new ArrayList<>(saved.size());
        int duplicated = 0;
        for (int index = 0; index < saved.size(); index++) {
            AuditLogRepository.SaveResult item = saved.get(index);
            if (item.duplicated()) {
                duplicated++;
                log.info("审计幂等命中，不再重复落库: action={}, idempotencyKey={}, id={}",
                        commands.get(index).action(), records.get(index).getIdempotencyKey(), item.id());
            }
            results.add(new AuditWriteResult(item.id(), item.duplicated()));
        }
        return new AuditBatchWriteResult(commands.size() - duplicated, duplicated, results);
    }

    /**
     * 命令 → 待落库领域对象，并补齐服务端负责的字段（主键 / 来源服务 / 幂等键 / 时间）。
     *
     * <p><b>主键口径</b>：由应用侧生成（{@link AuditIdGenerator}）。批量上报要压成一条多行 INSERT，
     * 插入后无法逐条回读主键，而接口契约要逐条回执 ID、台账也要在抢占幂等键时写下记录 ID，
     * 因此主键必须在插入前确定；相应地持久化对象的主键策略是 {@code IdType.INPUT}。
     *
     * <p><b>发生时间口径（回归修复）</b>：上报体提供了 {@code occurredAt} 就以其为准（业务真实发生时间，
     * 允许早于落库时间）；<b>未提供或为空</b>时落 {@code created_at}（同一时刻），绝不写 NULL。
     * 历史缺陷正是「缺省写 NULL」——{@code iam_audit_log.occurred_at} 全为 NULL 后，
     * 按发生时间的筛选/排序/时间范围过滤会整段漏行。
     */
    private AuditLog toLog(AuditRecordCommand command, String sourceService) {
        AuditLog log = command.toDomain();
        log.setId(idGenerator.nextId());
        log.setSourceService(log.getSourceService() == null ? sourceService : log.getSourceService());
        log.setIdempotencyKey(idempotencyKey(command));
        LocalDateTime createdAt = LocalDateTime.now();
        log.setCreatedAt(createdAt);
        if (log.getOccurredAt() == null) {
            log.setOccurredAt(createdAt);
        }
        return log;
    }

    /** 幂等键：显式键 > 请求级派生键 > 随机键（见类注释）。 */
    private String idempotencyKey(AuditRecordCommand command) {
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            return command.idempotencyKey();
        }
        String correlation = command.requestId() != null && !command.requestId().isBlank()
                ? command.requestId()
                : command.traceId();
        if (correlation == null || correlation.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        String raw = command.tenantId() + "|" + command.action() + "|" + correlation + "|"
                + nullSafe(command.resourceType()) + "|" + nullSafe(command.resourceId());
        return sha256(raw);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(Character.forDigit((item >> 4) & 0xF, 16));
                builder.append(Character.forDigit(item & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成审计幂等键", exception);
        }
    }
}
