package io.openware.im.message.domain.moderation;

import java.util.List;

/** 已启用审核词来源：由管理服务下发（含缓存），消息服务据此在发送链路过滤。 */
public interface ModerationWordSource {
  List<ModerationWordRule> enabledWords();
}
