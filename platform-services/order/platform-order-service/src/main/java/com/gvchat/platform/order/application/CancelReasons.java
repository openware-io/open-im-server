package com.gvchat.platform.order.application;

import com.gvchat.common.exception.ApiException;

/**
 * 取消原因规则（预约取消 / 订单取消共用）：运营代客取消必须留下原因，不允许空白。
 *
 * <p>口径：
 * <ul>
 *   <li>空白（null/空串/全空格）→ 400 {@code CANCEL_REASON_REQUIRED}，消息由调用方给出
 *       （预约是「取消预约必须填写原因」，订单是「取消订单必须填写原因」）；</li>
 *   <li>长度上限 {@value #MAX_LENGTH}，与其它自由文本（{@link ItemDescriptions}）保持一致，
 *       超限 → 400 {@code CANCEL_REASON_TOO_LONG}；</li>
 *   <li>返回去除首尾空白后的文本，落审计的就是运营真正填的内容。</li>
 * </ul>
 *
 * <p>必填只对取消类动作生效：历史调用方沿用选填语义时走 {@link #optional(String)}
 * （如既有「作废订单」按钮），长度上限仍然一致，避免同一后台两种文本限制。
 */
public final class CancelReasons {

    public static final int MAX_LENGTH = 255;

    /** 取消原因为空。 */
    public static final String CODE_REQUIRED = "CANCEL_REASON_REQUIRED";
    /** 取消原因超长。 */
    public static final String CODE_TOO_LONG = "CANCEL_REASON_TOO_LONG";

    private CancelReasons() {
    }

    /** 必填校验：空白抛 {@code CANCEL_REASON_REQUIRED}（携带调用方给出的中文提示）。 */
    public static String require(String reason, String requiredMessage) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, CODE_REQUIRED, requiredMessage);
        }
        return trimToLimit(reason);
    }

    /** 选填校验：空白归一为 null（保留既有「不发原因」的调用方），超长仍拒绝。 */
    public static String optional(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return trimToLimit(reason);
    }

    private static String trimToLimit(String reason) {
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new ApiException(400, CODE_TOO_LONG,
                    "取消原因长度不能超过 " + MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }
}
