package io.openware.im.message.domain.secretmessage.port;

import java.util.List;

/** 私密消息延迟销毁发布端口：接收方已读计时后推送 MQ 延迟消息，到期精确销毁。 */
public interface SecretDestroyDelayedPublisher {
  /**
   * 发布延迟销毁命令（延迟 [delaySeconds] 秒后投递）。
   *
   * @return true=已投递；false=延迟超过 MQ 上限（如 1d 策略）未投递，由周期扫描兜底。
   */
  boolean publish(long secretChatId, List<String> msgIds, int delaySeconds);
}
