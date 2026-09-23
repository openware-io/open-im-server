package io.openware.im.message.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 编辑消息请求：发送后 2 分钟内仅发送者本人可编辑正文。 */
public record EditMessageRequest(@NotBlank String msgId, @NotBlank String newContent) {
}
