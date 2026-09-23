package io.openware.im.message.domain.secretmessage.repository;

import io.openware.im.message.domain.secretmessage.model.SecretMessageDestroyed;
import java.time.LocalDateTime;
import java.util.List;

/** 私密消息销毁痕迹仓库：仅登记 msgId + 销毁时刻 + 原因（无密文/内容）。 */
public interface SecretMessageDestroyedRepository {
  void save(SecretMessageDestroyed destroyed);

  /** 销毁时刻晚于 [afterDestroyAt] 的痕迹（按 destroyAt 升序），供离线端增量同步。 */
  List<SecretMessageDestroyed> listAfter(long secretChatId, LocalDateTime afterDestroyAt, int limit);
}
