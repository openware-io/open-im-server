package com.gvchat.platform.admin.infra;

import com.gvchat.infrastructure.audit.AuditActions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 后台写操作 → 审计动作码推导器。
 *
 * <p>口径（SAAS_PLATFORM_02 §9.4「操作日志要能按动作稳定码检索」）：
 * <ol>
 *   <li>先命中 {@link #RULES} 显式规则（含 {@code {id}} 归一化后匹配）：动作码语义精确，如
 *       {@code POST /admin/reservations/{id}/confirm → reservation.confirm}；</li>
 *   <li>未命中时按 {@code <模块>.<对象>.<动作>} 规则推导：模块取 /admin 后第一段，
 *       对象取第一个非数字段并做单复数归一，动作由末段语义或 HTTP 方法决定；</li>
 *   <li>仍无法归类 → {@code admin.operation}（「后台操作」），仍然落库、仍然带 path，
 *       不会出现「没见过的路由就不留痕」。</li>
 * </ol>
 * 中文标签统一由 {@link AuditActions#labelOf(String)} 生成，前端不依赖本服务的映射表。
 */
public final class AuditActionResolver {

    /** 资源类型/模块名归一：路径段 → 稳定模块码。 */
    private static final Map<String, String> MODULES = new LinkedHashMap<>();

    /** 归一化路径（数字段替换为 {id}）→ 动作码。 */
    private static final Map<String, String> RULES = new LinkedHashMap<>();

    /** 末段语义 → 动作码动词。 */
    private static final Map<String, String> TAIL_VERBS = new LinkedHashMap<>();

    static {
        MODULES.put("staff", "staff");
        MODULES.put("media", "media");
        MODULES.put("reservations", "reservation");
        MODULES.put("menus", "menu");
        MODULES.put("backends", "backend");
        MODULES.put("contexts", "context");
        MODULES.put("context", "context");
        MODULES.put("auth", "auth");
        MODULES.put("reports", "report");
        MODULES.put("ktv", "ktv");
        MODULES.put("pricing-plans", "pricing-plan");
        MODULES.put("payment-switches", "payment-method");
        MODULES.put("server-catalog", "ktv-server");
        MODULES.put("images", "media-image");

        TAIL_VERBS.put("confirm", "confirm");
        TAIL_VERBS.put("arrival", "arrival");
        TAIL_VERBS.put("cancel", "cancel");
        TAIL_VERBS.put("open-table", "ktv_session.open");
        TAIL_VERBS.put("status", "update");
        TAIL_VERBS.put("im-binding", "update");
        TAIL_VERBS.put("export", "export");
        TAIL_VERBS.put("download", "download");
        TAIL_VERBS.put("import", "import");
        TAIL_VERBS.put("upload", "upload");
        TAIL_VERBS.put("publish", "publish");
        TAIL_VERBS.put("unpublish", "unpublish");

        RULES.put("POST /admin/media/images", "media.image.upload");
        RULES.put("POST /admin/auth/login", "auth.login");
        RULES.put("POST /admin/auth/sso", "auth.login");
        RULES.put("POST /admin/auth/logout", "auth.logout");
        RULES.put("POST /admin/auth/password", "auth.password.change");
        RULES.put("POST /admin/context/select", "context.select");
        RULES.put("POST /admin/staff", "staff.create");
        RULES.put("PATCH /admin/staff/{id}", "staff.update");
        RULES.put("PATCH /admin/staff/{id}/status", "staff.update");
        RULES.put("PATCH /admin/staff/{id}/im-binding", "staff.update");
        RULES.put("DELETE /admin/staff/{id}/im-binding", "staff.unbind");
        RULES.put("DELETE /admin/staff/{id}", "staff.delete");
        RULES.put("POST /admin/reservations/{id}/confirm", "reservation.confirm");
        RULES.put("POST /admin/reservations/{id}/arrival", "reservation.arrival");
        RULES.put("POST /admin/reservations/{id}/cancel", "reservation.cancel");
        RULES.put("POST /admin/reservations/{id}/assign-room", "reservation.assign_room");
        RULES.put("POST /admin/reservations/{id}/no-show", "reservation.no_show");
        RULES.put("POST /admin/reservations/{id}/open-table", "order.ktv_session.open");
        RULES.put("POST /admin/ktv/pricing-plans", "pricing-plan.create");
        RULES.put("PUT /admin/ktv/pricing-plans/{id}", "pricing-plan.update");
        RULES.put("POST /admin/ktv/payment-switches", "payment-method.create");
        RULES.put("PUT /admin/ktv/payment-switches/{id}", "payment-method.update");
        RULES.put("POST /admin/ktv/server-catalog", "ktv-server.create");
        RULES.put("PUT /admin/ktv/server-catalog/{id}", "ktv-server.update");
        // 储值充值/退还不再经本 BFF（/admin/ktv/wallet-recharge 已删除）：储值唯一入口是「储值管理」页，
        // 直连 customer 域 /admin/wallets/*，审计由 customer 域的 wallet.recharge / wallet.refund 落库。
    }

    private AuditActionResolver() {}

    /** 推导结果：动作码 + 中文标签 + 资源三元组。 */
    public record Resolved(String action, String actionLabel, String resourceType, String resourceId) { }

    /** 写操作（POST/PUT/PATCH/DELETE）一律审计，读取类不落库以免噪声。 */
    public static boolean isWrite(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    /**
     * 敏感读例外：导出/下载属于「数据出境」行为，业界规范（GDPR/SOC2 操作日志）要求留痕。
     *
     * <p>只放行明确带 export/download 语义的 GET，其余读取仍然不入库。
     */
    public static boolean isSensitiveRead(String method, String path) {
        if (!"GET".equals(method)) {
            return false;
        }
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.endsWith("/export") || lower.contains("/export/")
                || lower.endsWith("/download") || lower.contains("/download/");
    }

    /** 按方法与路径推导动作码。 */
    public static Resolved resolve(String method, String path) {
        List<String> segments = segments(path);
        String normalized = normalize(segments);
        String resourceId = segments.stream().filter(AuditActionResolver::isNumeric).findFirst().orElse(null);
        String ruleAction = RULES.get(method + " " + normalized);
        if (ruleAction != null) {
            return new Resolved(ruleAction, AuditActions.labelOf(ruleAction), resourceTypeOf(ruleAction, segments),
                    resourceId);
        }
        if (segments.size() < 2) {
            return new Resolved("admin.operation", AuditActions.labelOf("admin.operation"), null, resourceId);
        }
        String first = segments.get(1);
        String module = MODULES.getOrDefault(first, first);
        String second = segments.size() > 2 && !isNumeric(segments.get(2)) ? singular(segments.get(2)) : null;
        String tail = segments.get(segments.size() - 1);
        String verb = TAIL_VERBS.getOrDefault(tail, defaultVerb(method));
        StringBuilder action = new StringBuilder(module);
        // 末段本身就是动作时不再重复拼接（如 /admin/reports/export → report.export，而不是 report.export.export）。
        if (second != null && !second.equals(module) && !second.equals(verb)) {
            action.append('.').append(second);
        }
        action.append('.').append(verb);
        return new Resolved(action.toString(), AuditActions.labelOf(action.toString()),
                second == null ? module : second, resourceId);
    }

    /** 资源类型：取动作码模块段（如 media.image.upload → media），保证与动作码同源、可检索。 */
    private static String resourceTypeOf(String action, List<String> segments) {
        String[] parts = action.split("\\.");
        if (parts.length >= 1 && !parts[0].isBlank()) {
            return parts[0];
        }
        return segments.size() > 1 ? MODULES.getOrDefault(segments.get(1), segments.get(1)) : null;
    }

    private static String defaultVerb(String method) {
        return switch (method) {
            case "POST" -> "create";
            case "PUT", "PATCH" -> "update";
            case "DELETE" -> "delete";
            default -> "operation";
        };
    }

    /** 路径段（去掉空段），如 /admin/staff/12/status → [admin, staff, 12, status]。 */
    private static List<String> segments(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(path.split("/")).filter(segment -> !segment.isBlank()).toList();
    }

    /** 数字段归一为 {id}，用于规则匹配。 */
    private static String normalize(List<String> segments) {
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            builder.append('/').append(isNumeric(segment) ? "{id}" : segment);
        }
        return builder.toString();
    }

    private static boolean isNumeric(String value) {
        return value != null && value.matches("\\d{1,19}");
    }

    /** 单复数归一：仅当去掉尾部 s 后能命中已知模块时才归一，避免把 switches 变成 switche。 */
    private static String singular(String segment) {
        if (MODULES.containsKey(segment) || !segment.endsWith("s")) {
            return segment;
        }
        String candidate = segment.substring(0, segment.length() - 1);
        return MODULES.containsKey(candidate) ? candidate : segment;
    }
}
