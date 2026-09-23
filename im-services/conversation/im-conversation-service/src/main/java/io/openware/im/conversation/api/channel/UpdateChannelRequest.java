package io.openware.im.conversation.api.channel;

import jakarta.validation.constraints.Size;

/** 更新频道信息请求（仅 owner 可调用；字段缺省则保留原值）。 */
public record UpdateChannelRequest(
    @Size(max = 64) String name,
    @Size(max = 512) String avatar,
    @Size(max = 2000) String announcement) {
}
