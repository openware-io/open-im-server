package io.openware.im.conversation.domain.secretchat.port;

import java.util.List;
import java.util.Map;

/** 查询用户设备公钥（供私密聊天建立时预填对端公钥，实现「无需对方接受/在线即可发送」）。 */
public interface DeviceKeyPort {
  /** 返回 userId -> 最新 active 设备公钥；无公钥的用户不出现。 */
  Map<Long, String> findLatestByUserIds(List<Long> userIds);
}
