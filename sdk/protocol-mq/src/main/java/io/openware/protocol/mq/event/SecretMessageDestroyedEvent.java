package io.openware.protocol.mq.event;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 私密消息销毁事件：服务端调度器销毁后发布，携带被销毁的 msgId 列表（无密文/内容）。
 *
 * <p>im-access-ws 消费后对**在线**参与方主动推送 WS 销毁事件（客户端立即移除本地），
 * 离线参与方由 states 增量拉取兜底。销毁状态始终以服务端为准，端侧不持有销毁计时。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecretMessageDestroyedEvent {
  private String eventId;
  private long secretChatId;
  private List<String> msgIds;
  private List<Long> participantIds;
  private Instant destroyedAt;
  /** 销毁原因：destroyed=定时销毁、recalled=撤回、deleted=删除。 */
  private String reason;
}
