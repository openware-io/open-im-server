package io.openware.common.sms.domain.model;

/**
 * 短信发送日志领域实体：手机号只落摘要与脱敏展示，不落明文。
 *
 * <p><b>为什么是不可变 record 且不带审计字段</b>（standards/10 §聚合装配与持久化契约）：
 * {@code createdAt}/{@code updatedAt} 是记账信息而非业务事实，由适配器在写库时填充；
 * 领域模型携带它们会迫使领域层理解持久化语义。状态迁移通过 {@link #markSent()}/{@link #markFailed()}
 * 这类具名方法表达，而不是对外暴露整对象的 setter——后者让调用方能绕过状态机直接改任意字段。
 *
 * <p><b>字段口径</b>：{@code phoneDigest} 与 {@code phoneMasked} 是同一手机号的两种表示，
 * 明文既不落库也不进领域模型。{@code status} 取值见 {@link #STATUS_PENDING} 等常量。
 */
public record SmsSendLog(
        Long id,
        Long tenantId,
        String phoneDigest,
        String phoneMasked,
        String templateCode,
        String provider,
        String status,
        Integer retryCount,
        String providerMessageId,
        String rawResponse,
        String idempotencyKey) {

    /** 已受理、尚未提交给 provider。 */
    public static final String STATUS_PENDING = "PENDING";
    /** provider 已受理。 */
    public static final String STATUS_SENT = "SENT";
    /** provider 拒绝或不可达。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 新建一条待发送日志（未落库，{@code id} 由适配器回填）。 */
    public static SmsSendLog pending(Long tenantId, String phoneDigest, String phoneMasked, String templateCode,
                                     String provider, String idempotencyKey) {
        return new SmsSendLog(null, tenantId, phoneDigest, phoneMasked, templateCode, provider,
                STATUS_PENDING, 0, null, null, idempotencyKey);
    }

    /** provider 已受理：状态转 SENT 并记录其消息号与原始响应。 */
    public SmsSendLog markSent(String providerMessageId, String rawResponse) {
        return new SmsSendLog(id, tenantId, phoneDigest, phoneMasked, templateCode, provider,
                STATUS_SENT, retryCount, providerMessageId, rawResponse, idempotencyKey);
    }

    /** provider 拒绝或不可达：状态转 FAILED，保留原始响应以便排查。 */
    public SmsSendLog markFailed(String rawResponse) {
        return new SmsSendLog(id, tenantId, phoneDigest, phoneMasked, templateCode, provider,
                STATUS_FAILED, retryCount, providerMessageId, rawResponse, idempotencyKey);
    }

    /** 落库后回填自增主键（仅适配器使用）。 */
    public SmsSendLog withId(Long newId) {
        return new SmsSendLog(newId, tenantId, phoneDigest, phoneMasked, templateCode, provider,
                status, retryCount, providerMessageId, rawResponse, idempotencyKey);
    }
}
