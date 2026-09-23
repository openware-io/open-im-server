package io.openware.common.port;

import java.util.Map;

/**
 * IM 实时通知端口，定义向用户、群组或全局广播事件的抽象能力。
 * <p>
 * 由基础设施层实现，供领域层或应用层推WebSocket / SSE 等实时消息。
 * </p>
 */
public interface ImNotificationPort {

  /**
   * 向指定用户推送事件。
   *
   * @param userId 目标用户 ID
   * @param event 事件名称
   * @param data  事件附加数据
   */
  void sendToUser(long userId, String event, Map<String, Object> data);

  /**
   * 向指定群组推送事件。
   *
   * @param groupId 目标群组 ID
   * @param event  事件名称
   * @param data  事件附加数据
   */
  void sendToGroup(long groupId, String event, Map<String, Object> data);

  /**
   * 向所有在线客户端广播事件。
   *
   * @param event 事件名称
   * @param data 事件附加数据
   */
  void broadcast(String event, Map<String, Object> data);
}
