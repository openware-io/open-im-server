package io.openware.common.audit.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.audit.domain.model.AuditLog;
import io.openware.common.audit.domain.model.OperatorDisplay;
import io.openware.common.audit.domain.repository.AuditLogRepository;
import io.openware.common.audit.infra.persistence.mapper.IamAuditLogMapper;
import io.openware.common.audit.infra.persistence.mapper.OperatorNameMapper;
import io.openware.common.audit.infra.persistence.mapper.TenantNameMapper;
import io.openware.common.audit.infra.persistence.po.IamAuditLogPo;
import io.openware.common.audit.infra.persistence.row.OperatorNameRow;
import io.openware.common.audit.infra.persistence.row.TenantNameRow;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 审计日志仓储实现：MyBatis-Plus 持久化 + 唯一键幂等。
 *
 * <p>分页不使用 MyBatis-Plus 的分页插件（本服务没有注册 {@code PaginationInnerInterceptor}，
 * 缺插件时 {@code selectPage} 会静默退化为全量查询），而是显式 {@code count} + {@code LIMIT}：
 * 偏移量由应用层的整数页码换算，参数不进 SQL 拼接，避免注入与「分页失效」两类问题。
 */
@Slf4j
@Repository
public class AuditLogRepositoryImpl implements AuditLogRepository {

    /** countCap 缺省上界：调用方未指定时仍然避免无上界聚合。 */
    static final long DEFAULT_COUNT_CAP = 100_000L;

    /** 单条多行 INSERT 的最大行数：与上报接口的单批上限一致，避免超长 SQL 与包体过大。 */
    static final int MAX_INSERT_CHUNK = 200;

    private final IamAuditLogMapper mapper;
    private final TenantNameMapper tenantNameMapper;
    private final OperatorNameMapper operatorNameMapper;

    public AuditLogRepositoryImpl(IamAuditLogMapper mapper, TenantNameMapper tenantNameMapper,
                                 OperatorNameMapper operatorNameMapper) {
        this.mapper = mapper;
        this.tenantNameMapper = tenantNameMapper;
        this.operatorNameMapper = operatorNameMapper;
    }

    /**
     * 幂等写入：**先抢幂等台账**，抢到才写审计主表。
     *
     * <p>为什么不在主表上靠唯一键：主表要按 {@code occurred_at} 月分区，而 MySQL 要求分区表的
     * 每个唯一索引都包含分区列；唯一键一旦带上时间列，重试晚 1ms 就不再冲突、幂等静默失效。
     * 因此唯一性职责挪到 {@code iam_audit_idempotency}（见 V4 迁移），主表只管留痕。
     *
     * <p>三条路径：
     * <ol>
     *   <li>抢到键（受影响 1 行）→ 写主表并回填台账 {@code audit_id}；</li>
     *   <li>键已存在（受影响 0 行）→ 回执首次落库的 ID，{@code duplicated=true}，不再写主表；</li>
     *   <li>并发同时抢同一键 → 主键兜底，败者抛 {@code DuplicateKeyException}，按重复处理。
     *       台账有键但 {@code audit_id} 为空（理论上不该出现）时**按未写入补写**，
     *       而不是当重复丢掉——审计宁可重复也不能丢失。</li>
     * </ol>
     *
     * <p>两条写入在同一事务内（上层 {@code AuditLogApplicationService} 标了 {@code @Transactional}）：
     * 主表写失败整体回滚，不会留下「抢到键但没记录」的中间态。
     */
    @Override
    public SaveResult saveIfAbsent(AuditLog entry) {
        requireId(entry);
        if (!claim(entry)) {
            Long existingId = mapper.findClaimedAuditId(entry.getTenantId(), entry.getIdempotencyKey());
            if (existingId != null) {
                return new SaveResult(existingId, true);
            }
            // 恢复分支：台账有键但 audit_id 为空（历史版本「先插主表再回填」的残留）。
            // 按未写入补写，并在写完主表后把 ID 补回台账——否则每次重试都会再插一条。
            log.warn("幂等台账命中但未回填审计ID，按未写入补写: tenantId={}, idempotencyKey={}",
                    entry.getTenantId(), entry.getIdempotencyKey());
            mapper.insert(toPo(entry));
            mapper.attachAuditIdIfAbsent(entry.getTenantId(), entry.getIdempotencyKey(), entry.getId());
            return new SaveResult(entry.getId(), false);
        }
        mapper.insert(toPo(entry));
        return new SaveResult(entry.getId(), false);
    }

    /**
     * 批量幂等写入：台账逐条抢占（**小表**，单行插入语义精确），审计主表**一次多行插入**。
     *
     * <p>主表是高频写入的大表，批量上报的主要成本就在这里；把 N 条逐行 INSERT 压成 1 条 SQL
     * 是本次「真实多行批插」的落点。台账仍逐条抢占，因为需要逐条的「首次/重复」判定——
     * 它体量小（只覆盖重试窗口），逐条成本可接受，换来的是与单条路径完全一致的幂等语义。
     *
     * <p>返回顺序与入参一一对应（调用方按位置回执逐条 ID）。
     */
    @Override
    public List<SaveResult> saveAllIfAbsent(List<AuditLog> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        entries.forEach(AuditLogRepositoryImpl::requireId);
        SaveResult[] results = new SaveResult[entries.size()];
        List<AuditLog> fresh = new ArrayList<>(entries.size());
        List<AuditLog> recovered = new ArrayList<>(0);
        for (int index = 0; index < entries.size(); index++) {
            AuditLog entry = entries.get(index);
            if (claim(entry)) {
                fresh.add(entry);
                results[index] = new SaveResult(entry.getId(), false);
                continue;
            }
            Long existingId = mapper.findClaimedAuditId(entry.getTenantId(), entry.getIdempotencyKey());
            if (existingId != null) {
                results[index] = new SaveResult(existingId, true);
            } else {
                // 与单条路径同一恢复口径：台账有键无记录时补写（宁可重复也不丢审计），写完补回填 ID。
                log.warn("幂等台账命中但未回填审计ID，按未写入补写: tenantId={}, idempotencyKey={}",
                        entry.getTenantId(), entry.getIdempotencyKey());
                fresh.add(entry);
                recovered.add(entry);
                results[index] = new SaveResult(entry.getId(), false);
            }
        }
        insertFresh(fresh);
        for (AuditLog entry : recovered) {
            mapper.attachAuditIdIfAbsent(entry.getTenantId(), entry.getIdempotencyKey(), entry.getId());
        }
        return List.of(results);
    }

    /** 主表分批多行插入：单条 SQL 的行数受 {@link #MAX_INSERT_CHUNK} 限制，避免超长 SQL。 */
    private void insertFresh(List<AuditLog> fresh) {
        for (int start = 0; start < fresh.size(); start += MAX_INSERT_CHUNK) {
            List<AuditLog> chunk = fresh.subList(start, Math.min(fresh.size(), start + MAX_INSERT_CHUNK));
            mapper.insertBatch(chunk.stream().map(this::toPo).toList());
        }
    }

    /** 抢占幂等键：并发抢同键时由主键兜底（败者按重复处理）。 */
    private boolean claim(AuditLog entry) {
        try {
            return mapper.claimIdempotencyKey(entry.getTenantId(), entry.getIdempotencyKey(), entry.getId(),
                    entry.getCreatedAt()) > 0;
        } catch (DuplicateKeyException concurrent) {
            return false;
        }
    }

    /** 主键由应用侧生成：缺失说明调用方漏生成，属于编程错误，必须立刻暴露而不是让库里自增。 */
    private static void requireId(AuditLog entry) {
        if (entry == null || entry.getId() == null || entry.getId() <= 0) {
            throw new IllegalArgumentException("审计记录缺少应用侧生成的主键（AuditIdGenerator）");
        }
    }

    @Override
    public Optional<AuditLog> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public List<AuditLog> findPage(Query query) {
        // 排序与 LIMIT 一起放进 last()：排序必须按「有效发生时间」 COALESCE(occurred_at, created_at)，
        // LambdaQueryWrapper 只能按单列排序，无法表达该表达式。
        LambdaQueryWrapper<IamAuditLogPo> wrapper = filter(query)
                .last(orderByClause(query.ascending()) + " LIMIT " + query.offset() + ", " + query.size());
        return mapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    /**
     * 排序子句：按「有效发生时间」排序，{@code occurred_at} 为空的历史行回退 {@code created_at}。
     *
     * <p>包级可见是为了让仓储层单测能直接断言排序口径（SQL 生成结果不适合在单测里硬解析）。
     *
     * @param ascending {@code true} 为时间正序（旧→新），否则倒序（新→旧，与前端默认一致）
     */
    static String orderByClause(boolean ascending) {
        String direction = ascending ? "ASC" : "DESC";
        return "ORDER BY COALESCE(occurred_at, created_at) " + direction + ", id " + direction;
    }

    @Override
    public long count(Query query) {
        // 带上界统计：最多证明「不少于 countCap 行」，让超大结果集能提前停止扫描（见 mapper 注释）。
        long cap = query.countCap() > 0 ? query.countCap() : DEFAULT_COUNT_CAP;
        Long total = mapper.countUpTo(filter(query), cap);
        return total == null ? 0L : total;
    }

    @Override
    public Map<Long, String> tenantNames(java.util.Collection<Long> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = tenantIds.stream().filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            List<TenantNameRow> rows = tenantNameMapper.selectNamesByIds(new ArrayList<>(ids));
            Map<Long, String> names = new HashMap<>();
            if (rows != null) {
                for (TenantNameRow row : rows) {
                    names.put(row.getId(), row.getName());
                }
            }
            return names;
        } catch (RuntimeException exception) {
            // 名称只影响展示，不能因为跨域表不可用让审计查询整体失败。
            log.warn("补全租户名称失败，按空名称返回: tenantIds={}, cause={}", ids, exception.getMessage());
            return Map.of();
        }
    }

    @Override
    public Map<Long, OperatorDisplay> operatorDisplays(java.util.Collection<Long> operatorIds) {
        if (operatorIds == null || operatorIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = operatorIds.stream().filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            // operator_id=0 表示「上报方没带操作人」（保留任务的自留审计就是这种），不必查账号表。
            return Map.of();
        }
        try {
            List<OperatorNameRow> rows = operatorNameMapper.selectByOperatorIds(new ArrayList<>(ids));
            Map<Long, OperatorDisplay> displays = new HashMap<>();
            if (rows != null) {
                for (OperatorNameRow row : rows) {
                    if (row.getOperatorId() == null) {
                        continue;
                    }
                    displays.put(row.getOperatorId(), new OperatorDisplay(row.getDisplayName(), row.getUsername()));
                }
            }
            return displays;
        } catch (RuntimeException exception) {
            // 姓名只影响展示，不能因为跨域表不可用让审计查询整体失败。
            log.warn("补全操作人姓名失败，按空值返回: operatorIds={}, cause={}", ids, exception.getMessage());
            return Map.of();
        }
    }

    /**
     * 关键字 → 候选操作人 ID（姓名/登录名模糊匹配）。
     *
     * <p>审计表里的 {@code operator_name} 可能为空（上报方没带姓名），只按审计表字段匹配会让
     * 「按姓名搜索」查不到老记录；这里先在账号表把关键字解析成 ID，再并到查询条件上。
     * 账号表不可用（缺授权/表不存在，H2 单测同样如此）时退化为空列表——搜索退化成原来的口径，
     * 但绝不能因此让列表整体失败。
     */
    private List<Long> operatorIdsByKeyword(String keyword) {
        try {
            List<Long> ids = operatorNameMapper.selectOperatorIdsByKeyword(keyword);
            return ids == null ? List.of() : ids.stream().filter(id -> id != null && id > 0).toList();
        } catch (RuntimeException exception) {
            log.warn("按关键字解析操作人账号失败，本次仅按审计表字段匹配: keyword={}, cause={}",
                    keyword, exception.getMessage());
            return List.of();
        }
    }

    private LambdaQueryWrapper<IamAuditLogPo> filter(Query query) {
        LambdaQueryWrapper<IamAuditLogPo> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(query.tenantId() != null, IamAuditLogPo::getTenantId, query.tenantId());
        wrapper.eq(query.organizationId() != null, IamAuditLogPo::getOrganizationId, query.organizationId());
        wrapper.eq(query.storeId() != null, IamAuditLogPo::getStoreId, query.storeId());
        wrapper.eq(query.operatorId() != null, IamAuditLogPo::getOperatorId, query.operatorId());
        wrapper.eq(StringUtils.hasText(query.action()), IamAuditLogPo::getAction, query.action());
        wrapper.likeRight(StringUtils.hasText(query.actionPrefix()), IamAuditLogPo::getAction, query.actionPrefix());
        wrapper.eq(StringUtils.hasText(query.resourceType()), IamAuditLogPo::getResourceType, query.resourceType());
        wrapper.eq(StringUtils.hasText(query.resourceId()), IamAuditLogPo::getResourceId, query.resourceId());
        wrapper.eq(StringUtils.hasText(query.result()), IamAuditLogPo::getResult, query.result());
        wrapper.eq(StringUtils.hasText(query.operatorType()), IamAuditLogPo::getOperatorType, query.operatorType());
        wrapper.eq(StringUtils.hasText(query.requestId()), IamAuditLogPo::getRequestId, query.requestId());
        wrapper.eq(StringUtils.hasText(query.traceId()), IamAuditLogPo::getTraceId, query.traceId());
        applyTimeRange(wrapper, query.from(), query.to());
        if (StringUtils.hasText(query.operatorKeyword())) {
            String keyword = query.operatorKeyword().trim();
            Long operatorId = numeric(keyword);
            List<Long> matchedOperatorIds = operatorIdsByKeyword(keyword);
            // 操作人关键字同时匹配姓名、账号（登录名/工号）与账号 ID：运营排查时手上可能只有其中一个。
            // 姓名/账号为空的记录靠 matchedOperatorIds 这一路命中（审计表只冗余了 operator_id）。
            wrapper.and(nested -> {
                nested.like(IamAuditLogPo::getOperatorName, keyword)
                        .or().like(IamAuditLogPo::getOperatorAccount, keyword);
                if (operatorId != null) {
                    nested.or().eq(IamAuditLogPo::getOperatorId, operatorId);
                }
                if (!matchedOperatorIds.isEmpty()) {
                    nested.or().in(IamAuditLogPo::getOperatorId, matchedOperatorIds);
                }
            });
        }
        return wrapper;
    }

    private static Long numeric(String value) {
        return value.matches("\\d{1,19}") ? Long.valueOf(value) : null;
    }

    /**
     * 时间范围过滤（左闭右开）：以业务发生时间 {@code occurred_at} 为准。
     *
     * <p><b>查询侧防御</b>：{@code occurred_at} 为空的行视同发生在 {@code created_at}（等价
     * {@code COALESCE}），因此判定条件是
     * {@code occurred_at >= from OR (occurred_at IS NULL AND created_at >= from)}。
     * 直接用 {@code occurred_at >= from} 会把历史 NULL 行整段丢掉——这正是「时间筛选静默丢行」的现场；
     * 也不能只按 created_at 过滤：补录/延迟上报的记录（occurred_at 早于 created_at）会漏。
     *
     * <p>包级可见是为了让仓储层单测能断言生成的 SQL 片段（含 NULL 回退分支）。
     */
    static void applyTimeRange(LambdaQueryWrapper<IamAuditLogPo> wrapper, LocalDateTime from, LocalDateTime to) {
        if (from != null) {
            wrapper.and(scope -> scope.ge(IamAuditLogPo::getOccurredAt, from)
                    .or(fallback -> fallback.isNull(IamAuditLogPo::getOccurredAt)
                            .ge(IamAuditLogPo::getCreatedAt, from)));
        }
        if (to != null) {
            wrapper.and(scope -> scope.lt(IamAuditLogPo::getOccurredAt, to)
                    .or(fallback -> fallback.isNull(IamAuditLogPo::getOccurredAt)
                            .lt(IamAuditLogPo::getCreatedAt, to)));
        }
    }

    /**
     * 领域对象 → 持久化对象，并补齐 NOT NULL 列的缺省值。
     *
     * <p>为什么必须在这里补齐（而不是依赖数据库默认值）：
     * <b>多行 INSERT 会把这些列显式写出来，而数据库默认值只在「不指定该列」时生效</b>——
     * 显式写 {@code NULL} 会直接触发 1048（列不能为空）。单条路径过去是靠 MyBatis-Plus 默认跳过
     * null 字段间接吃到默认值，两条路径的口径必须一致，否则「单条能写、批量报错」极难定位。
     * 缺省值与迁移脚本里的列默认值保持一一对应。
     */
    private IamAuditLogPo toPo(AuditLog log) {
        IamAuditLogPo po = new IamAuditLogPo();
        po.setId(log.getId());
        po.setTenantId(log.getTenantId() == null ? 0L : log.getTenantId());
        po.setOrganizationId(log.getOrganizationId());
        po.setStoreId(log.getStoreId());
        po.setOperatorId(log.getOperatorId() == null ? 0L : log.getOperatorId());
        po.setOperatorName(log.getOperatorName());
        po.setOperatorAccount(log.getOperatorAccount());
        po.setOperatorType(log.getOperatorType() == null ? AuditLog.OPERATOR_TYPE_TENANT : log.getOperatorType());
        po.setAction(log.getAction());
        po.setActionLabel(log.getActionLabel() == null ? "" : log.getActionLabel());
        po.setResourceType(log.getResourceType() == null ? "" : log.getResourceType());
        po.setResourceId(log.getResourceId());
        po.setResourceName(log.getResourceName());
        po.setResult(log.getResult() == null ? AuditLog.RESULT_SUCCESS : log.getResult());
        po.setErrorCode(log.getErrorCode());
        po.setIp(log.getIp());
        po.setUserAgent(log.getUserAgent());
        po.setRequestId(log.getRequestId());
        po.setTraceId(log.getTraceId());
        po.setSourceService(log.getSourceService());
        po.setIdempotencyKey(log.getIdempotencyKey() == null ? "" : log.getIdempotencyKey());
        po.setDetailJson(log.getDetailJson());
        po.setOccurredAt(log.getOccurredAt());
        po.setCreatedAt(log.getCreatedAt());
        return po;
    }

    private AuditLog toDomain(IamAuditLogPo po) {
        AuditLog log = new AuditLog();
        log.setId(po.getId());
        log.setTenantId(po.getTenantId());
        log.setOrganizationId(po.getOrganizationId());
        log.setStoreId(po.getStoreId());
        log.setOperatorId(po.getOperatorId());
        log.setOperatorName(po.getOperatorName());
        log.setOperatorAccount(po.getOperatorAccount());
        log.setOperatorType(po.getOperatorType());
        log.setAction(po.getAction());
        log.setActionLabel(po.getActionLabel());
        log.setResourceType(po.getResourceType());
        log.setResourceId(po.getResourceId());
        log.setResourceName(po.getResourceName());
        log.setResult(po.getResult());
        log.setErrorCode(po.getErrorCode());
        log.setIp(po.getIp());
        log.setUserAgent(po.getUserAgent());
        log.setRequestId(po.getRequestId());
        log.setTraceId(po.getTraceId());
        log.setSourceService(po.getSourceService());
        log.setIdempotencyKey(po.getIdempotencyKey());
        log.setDetailJson(po.getDetailJson());
        log.setOccurredAt(po.getOccurredAt());
        log.setCreatedAt(po.getCreatedAt());
        return log;
    }
}
