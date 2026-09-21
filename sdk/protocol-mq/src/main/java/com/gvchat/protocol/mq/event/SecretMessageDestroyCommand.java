package com.gvchat.protocol.mq.event;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 私密消息延迟销毁命令：接收方已读计时后发布的 **MQ 延迟消息**（延迟 = ttl），
 * 到期由消息服务消费并精确销毁这批密文（仅 msgId，无密文/内容）。
 *
 * <p>在线端通过销毁事件（SecretMessageDestroyedEvent → WS 推送）立即移除；
 * 离线端重入时由 states 增量拉取删除。周期扫描作为兜底，防止延迟消息丢失。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecretMessageDestroyCommand {
  private String commandId;
  private long secretChatId;
  private List<String> msgIds;
  private Instant destroyAt;
}
