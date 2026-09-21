package com.gvchat.common.payment.channel.spi;

import java.util.Map;

/** 渠道回调请求；rawBody 为回调原始报文，headers 承载签名等元信息。 */
public record VerifyCallbackRequest(String rawBody, Map<String, String> headers) {
}
