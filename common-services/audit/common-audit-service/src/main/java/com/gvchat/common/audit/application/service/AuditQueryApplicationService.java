package com.gvchat.common.audit.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.audit.api.dto.AuditLogView;
import com.gvchat.common.audit.api.dto.AuditPageView;
import com.gvchat.common.audit.api.dto.AuditQueryRequest;
import com.gvchat.common.audit.application.AuditScope;
import com.gvchat.common.audit.application.AuditScopeResolver;
import com.gvchat.common.audit.application.retention.AuditRetentionPolicy;
import com.gvchat.common.audit.domain.model.AuditLog;
import com.gvchat.common.audit.domain.model.OperatorDisplay;
import com.gvchat.common.audit.domain.repository.AuditArchiveManifestRepository;
import com.gvchat.common.audit.domain.repository.AuditLogRepository;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditActions;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 审计查询应用服务：权限分层在这里落地，Controller 不做任何可见性判断。
 *
 * <p>分层规则见 {@link AuditScopeResolver}：平台视角跨租户（可带 tenantId 过滤），租户视角强制
 * {@code tenant_id = 当前上下文租户}；详情查询对越权租户返回 404（不泄露「记录是否存在」）。
 */
@Slf4j
@Service
public class AuditQueryApplicationService {

    /** 总数统计缺省上界：超过它只回「已封顶」，避免在超大结果集上做无上界聚合。 */
    public static final long DEFAULT_COUNT_CAP = 100_000L;

    private final AuditLogRepository repository;
    private final AuditScopeResolver scopeResolver;
    private final AuditRetentionPolicy retentionPolicy;
    private final AuditArchiveManifestRepository archiveManifest;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long countCap;

    public AuditQueryApplicationService(AuditLogRepository repository, AuditScopeResolver scopeResolver) {
        this(repository, scopeResolver, DEFAULT_COUNT_CAP, null, null);
    }

    /** 保留策略相关的协作者可缺省（既有单测装配）；缺省时不施加可查下限。 */
    public AuditQueryApplicationService(AuditLogRepository repository, AuditScopeResolver scopeResolver,
                                        long countCap) {
        this(repository, scopeResolver, countCap, null, null);
    }

    @Autowired
    public AuditQueryApplicationService(AuditLogRepository repository, AuditScopeResolver scopeResolver,
                                        @Value("${audit.query.count-cap:" + DEFAULT_COUNT_CAP + "}") long countCap,
                                        AuditRetentionPolicy retentionPolicy,
                                        AuditArchiveManifestRepository archiveManifest) {
        this.repository = repository;
        this.scopeResolver = scopeResolver;
        this.countCap = countCap > 0 ? countCap : DEFAULT_COUNT_CAP;
        this.retentionPolicy = retentionPolicy;
        this.archiveManifest = archiveManifest;
    }

    /**
     * 分页查询：任何客户端参数都不能突破视角边界。
     *
     * <p>总数统计的两种省成本路径（审计表是高频写入的大表，列表接口的主要成本就是 COUNT）：
     * <ul>
     *   <li>{@code skipCount=true}（前端第 2 页起回传）：完全跳过 COUNT，回 {@code total=-1}；</li>
     *   <li>否则统计到 {@code audit.query.count-cap} 为止，到顶回 {@code totalCapped=true}。</li>
     * </ul>
     *
     * <p>保留策略：早于 {@code retentionFloor} 的月份已登记归档并移出可查范围，
     * 这里把请求的左边界**收敛到下限**，避免查询去扫已归档的月份（也在响应里显式回执该下限）。
     */
    public AuditPageView list(AuditQueryRequest rawRequest) {
        AuditQueryRequest request = rawRequest.normalized();
        AuditScopeResolver.Resolution resolution = scopeResolver.resolve(request.tenantId());
        return page(request, resolution);
    }

    /**
     * 内部服务查询（IM 后台「审计日志」页走 {@code POST /internal/audit/records/search}）。
     *
     * <p>与公开端点 {@link #list} 的差别**只有视角来源**：本方法由内部 HMAC 通道调用
     * （{@code InternalAuditAuthenticationFilter} 校验签名 + 来源白名单，只放审计上报通道身份），
     * 调用方不是 SaaS 运营上下文，因此没有 {@code audit.view} 权限码可判，也不做租户收敛。
     * 可见范围仍由服务端决定（这里固定平台视角、全租户），客户端参数只能收窄、不能扩大。
     *
     * <p>页大小与时间区间由控制器统一夹取/解析，本方法不再重复校验。
     */
    public AuditPageView searchInternal(AuditQueryRequest rawRequest) {
        AuditQueryRequest request = rawRequest.normalized();
        AuditScopeResolver.Resolution resolution =
                new AuditScopeResolver.Resolution(AuditScope.PLATFORM, null, null);
        return page(request, resolution);
    }

    /** 分页查询主体：视角解析完成后，两种入口（公开 / 内部）共用同一套过滤与回执逻辑。 */
    private AuditPageView page(AuditQueryRequest request, AuditScopeResolver.Resolution resolution) {
        LocalDateTime floor = retentionFloor();
        AuditLogRepository.Query query = request.toQuery(resolution, countCap, floor);
        boolean skipped = request.skipCount();
        // 上界在应用层再夹一次：仓储实现无论返回什么，对外的 total 都不会超过 countCap
        // （total 在封顶时只是「不少于」，由 totalCapped 显式表达，前端据此显示 N+）。
        long total = skipped ? AuditPageView.TOTAL_SKIPPED : Math.min(repository.count(query), countCap);
        boolean totalCapped = !skipped && total >= countCap;
        List<AuditLog> logs = repository.findPage(query);
        enrichOperatorDisplays(logs);
        Map<Long, String> tenantNames = repository.tenantNames(
                logs.stream().map(AuditLog::getTenantId).toList());
        List<AuditLogView> items = new ArrayList<>(logs.size());
        for (AuditLog log : logs) {
            items.add(AuditLogView.of(log, tenantNames.get(log.getTenantId()), detail(log)));
        }
        int totalPages = skipped || request.pageSize() <= 0
                ? 0
                : (int) ((total + request.pageSize() - 1) / request.pageSize());
        return new AuditPageView(resolution.scope().name(), resolution.tenantId(), request.page(),
                request.pageSize(), total, totalPages, totalCapped,
                floor == null ? null : floor.toString(), items);
    }

    /**
     * 补全操作人姓名/账号：审计表冗余了 {@code operator_id}，姓名与登录名按它到账号表取。
     *
     * <p>口径是「已存值优先、只在缺失时补」，两条理由：
     * <ul>
     *   <li>记录里存下的姓名是**动作发生当时**的快照，账号改名不该追改历史审计；</li>
     *   <li>上报方没带姓名时（历史数据、保留任务的自留审计等）也要让运营看到「谁做的」，
     *       而不是一行 {@code #1}。补全只影响本次返回，不写回审计表（审计记录只读）。</li>
     * </ul>
     * 一次请求最多补一页（100 条）去重后的账号，账号表查询失败时仓储层已降级为空 Map，列表照常返回。
     */
    private void enrichOperatorDisplays(List<AuditLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        Map<Long, OperatorDisplay> displays = repository.operatorDisplays(
                logs.stream().map(AuditLog::getOperatorId).toList());
        if (displays.isEmpty()) {
            return;
        }
        for (AuditLog log : logs) {
            OperatorDisplay display = displays.get(log.getOperatorId());
            if (display == null) {
                continue;
            }
            if (!StringUtils.hasText(log.getOperatorName()) && StringUtils.hasText(display.name())) {
                log.setOperatorName(display.name());
            }
            if (!StringUtils.hasText(log.getOperatorAccount()) && StringUtils.hasText(display.account())) {
                log.setOperatorAccount(display.account());
            }
        }
    }

    /** 可查下限：由归档清单推导（没有被归档的月份时不设下限）。 */
    private LocalDateTime retentionFloor() {
        if (retentionPolicy == null || archiveManifest == null) {
            return null;
        }
        try {
            return retentionPolicy.queryFloor(archiveManifest.archivedMonths());
        } catch (RuntimeException failure) {
            // 清单读不出来不能把列表整体打死：退化为「不设下限」，按 WARN 暴露。
            log.warn("读取归档清单失败，本次查询不施加可查下限: cause={}", failure.getMessage());
            return null;
        }
    }

    /** 详情查询：租户视角下不属于本租户的记录按 404 处理。 */
    public AuditLogView detail(long id) {
        AuditScopeResolver.Resolution resolution = scopeResolver.resolve(null);
        AuditLog log = repository.findById(id)
                .orElseThrow(() -> new ApiException(404, "AUDIT_NOT_FOUND", "审计记录不存在"));
        if (!resolution.isPlatform() && !resolution.tenantId().equals(log.getTenantId())) {
            throw new ApiException(404, "AUDIT_NOT_FOUND", "审计记录不存在");
        }
        enrichOperatorDisplays(List.of(log));
        String tenantName = repository.tenantNames(List.of(log.getTenantId())).get(log.getTenantId());
        return AuditLogView.of(log, tenantName, detail(log));
    }

    /** 已登记动作码字典，供前端筛选下拉与运维核对接入范围（同样需要 audit.view）。 */
    public List<Map<String, String>> actions() {
        scopeResolver.resolve(null);
        return AuditActions.catalog();
    }

    /**
     * 详情投影：把库里存的 {@code detail_json} 文本规范化成**一段合法 JSON 文本**原样返回。
     *
     * <p>为什么返回 String 而不是 {@code JsonNode}：本工程是 Spring Boot 4 / Spring Framework 7，
     * HTTP 消息转换器用 Jackson 3（{@code tools.jackson.*}），而 Jackson 2 的 {@code JsonNode} 在
     * Jackson 3 里只是普通 POJO —— 响应体会把它序列化成
     * {@code {"array":false,...,"nodeType":"OBJECT",...}} 这种元数据，业务内容丢失（真机验收实测）。
     * 因此返回类型必须是 {@code String}：由 {@code @JsonRawValue} 之类的机制或普通字符串承载，
     * 前端拿到的是可 {@code JSON.parse} 的业务对象文本。
     *
     * <p>降级口径（不为了详情把整页查询打死）：
     * <ul>
     *   <li>缺失/空白 → {@code null}（前端显示「-」）；</li>
     *   <li>合法 JSON → 规范化后的同一段 JSON 文本（对象/数组/标量都原样保留）；</li>
     *   <li>历史脏数据（不是合法 JSON，例如纯文本 {@code legacy-text}）→ 用 JSON 字符串字面量包一层，
     *       返回 {@code "legacy-text"}（含引号），保证响应体里这一段是合法 JSON 且原文不丢。</li>
     * </ul>
     */
    private String detail(AuditLog log) {
        String raw = log.getDetailJson();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            // 先确认是合法 JSON：合法就原样输出（不改写既有数据格式，也不做二次序列化）。
            objectMapper.readTree(raw);
            return raw;
        } catch (Exception exception) {
            // 非 JSON 文本：包成 JSON 字符串字面量（"原始文本"），前端 JSON.parse 得到原字符串。
            return objectMapper.getNodeFactory().textNode(raw).toString();
        }
    }
}
