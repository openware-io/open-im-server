package io.openware.im.message.domain.message.event;

import java.time.Instant;
import java.util.List;

/**
 * 消息编辑领域事件：发送者在 2 分钟窗口内编辑消息正文后发布。
 * <p>负载只保留投影与接入层下行所需事实：新正文、消息定位、接收方与编辑时间。
 * 接入层（im-access-ws）据此向发送者其它设备与接收方设备下行「消息已编辑」更新；
 * MongoDB 热消息投影据此同步正文与 edited 标记。
 *
 * <p>注意：MQ 主题常量按工程规范应集中登记于 protocol-mq（ImMqTopics）。
 * 当前消息域变更范围受限，暂以 {@link #TOPIC} 存于本域；跨服务接入落地后需迁移至协议模块并在 MQ 台账登记。
 */
public record MessageEditedEvent(
    String eventId,
    String msgId,
    String conversationId,
    long seq,
    long senderId,
    String chatType,
    String toId,
    String content,
    List<Long> recipientUserIds,
    Instant editedAt) {

  /** 消息编辑事件主题（待集中登记至 protocol-mq，见类注释）。 */
  public static final String TOPIC = io.openware.protocol.mq.topic.ImMqTopics.MESSAGE_EDITED_EVENT;
}
