package io.openware.im.user.infra.messaging.outbox;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.im.user.infra.messaging.outbox.mapper.UserOutboxMapper;
import io.openware.im.user.infra.messaging.outbox.po.UserOutboxPo;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserOutboxRelay {
  private final UserOutboxMapper userOutboxMapper;
  private final MqProducer mqProducer;
  @Value("${im.user.outbox.batch-size:100}")
  private int batchSize;

  @Scheduled(fixedDelayString = "${im.user.outbox.relay-delay-ms:1000}")
  @Transactional
  public void relayPendingEvents() {
    List<UserOutboxPo> pending = userOutboxMapper.selectList(Wrappers.<UserOutboxPo>lambdaQuery()
        .eq(UserOutboxPo::getPublished, Boolean.FALSE)
        .orderByAsc(UserOutboxPo::getId)
        .last("LIMIT " + batchSize));
    for (UserOutboxPo row : pending) {
      try {
        mqProducer.sendOrdered(row.getTopic(), row.getShardingKey(), row.getEventId(),
            row.getPayloadJson().getBytes(StandardCharsets.UTF_8));
        row.setPublished(Boolean.TRUE);
        row.setPublishedAt(LocalDateTime.now());
        userOutboxMapper.updateById(row);
        log.info("用户 Outbox 事件转发成功, eventId={}, topic={}, shardingKey={}", row.getEventId(), row.getTopic(),
            row.getShardingKey());
      } catch (Exception exception) {
        log.error("用户 Outbox 事件转发失败, eventId={}, topic={}, shardingKey={}", row.getEventId(), row.getTopic(),
            row.getShardingKey(), exception);
        throw new IllegalStateException("用户 Outbox 事件转发失败, eventId=" + row.getEventId(), exception);
      }
    }
  }
}
