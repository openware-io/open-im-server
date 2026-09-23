package io.openware.infrastructure.mq.remoting;

import static org.assertj.core.api.Assertions.assertThat;

import io.openware.common.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.jupiter.api.Test;

/**
 * 顺序消费「毒消息」治理。
 *
 * <p>回归背景：有序队列一旦某条消息始终失败，位点不再推进，该队列后续消息全部无法落库；发送方仍能拿到
 * ack，因此故障是静默的。生产环境已出现过一条「不是好友」（HTTP 403）的消息把队列挂死、之后所有消息
 * 都不落库的故障。以下用例锁定「不可恢复失败必须终止并继续」这一保证。
 */
class RemotingMqConsumerFactoryTest {
  private static final String TOPIC = "im_message_command_send_v1";
  private static final String GROUP = "im-message-service-write-command-consumer";

  private final RemotingMqConsumerFactory factory = new RemotingMqConsumerFactory("127.0.0.1:9876");

  @Test
  void newConsumerGroupsStartFromLatestOffset() {
    assertThat(RemotingMqConsumerFactory.initialConsumeFromWhere())
        .isEqualTo(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
  }

  @Test
  void unrecoverableBusinessRejectionDoesNotBlockTheQueue() {
    List<String> handled = new ArrayList<>();
    ApiException notFriends = new ApiException(403, "Not friends");

    ConsumeOrderlyStatus status = factory.consume(TOPIC, GROUP, List.of(message("poison")), context(),
        body -> {
          handled.add(new String(body, StandardCharsets.UTF_8));
          throw notFriends;
        });

    // 关键：返回 SUCCESS 才会推进位点；若返回 SUSPEND，队列将被永久挂起。
    assertThat(status).isEqualTo(ConsumeOrderlyStatus.SUCCESS);
    assertThat(handled).containsExactly("poison");
  }

  @Test
  void unrecoverableRejectionLetsFollowingMessagesBeConsumed() {
    List<String> handled = new ArrayList<>();

    ConsumeOrderlyStatus status = factory.consume(TOPIC, GROUP,
        List.of(message("poison"), message("after-1"), message("after-2")), context(), body -> {
          handled.add(new String(body, StandardCharsets.UTF_8));
          if (body.equals("poison")) {
            throw new ApiException(403, "Not friends");
          }
        });

    assertThat(status).isEqualTo(ConsumeOrderlyStatus.SUCCESS);
    assertThat(handled).containsExactly("poison", "after-1", "after-2");
  }

  @Test
  void malformedPayloadIsTreatedAsUnrecoverable() {
    List<String> handled = new ArrayList<>();

    ConsumeOrderlyStatus status = factory.consume(TOPIC, GROUP, List.of(message("bad-contract")), context(),
        body -> {
          handled.add(new String(body, StandardCharsets.UTF_8));
          throw new IllegalArgumentException("Unknown ChatType: GROUP");
        });

    assertThat(status).isEqualTo(ConsumeOrderlyStatus.SUCCESS);
    assertThat(handled).containsExactly("bad-contract");
  }

  @Test
  void transientFailureIsRetriedAndSuspendsTheQueue() {
    ConsumeOrderlyStatus status = factory.consume(TOPIC, GROUP, List.of(message("flaky")), context(),
        body -> {
          throw new IllegalStateException("database unavailable");
        });

    assertThat(status).isEqualTo(ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT);
  }

  @Test
  void transientFailureStopsBlockingTheQueueAfterMaxAttempts() {
    MessageExt flaky = message("always-flaky");
    ConsumeOrderlyStatus status = null;
    for (int i = 0; i < 5; i++) {
      status = factory.consume(TOPIC, GROUP, List.of(flaky), context(),
          body -> {
            throw new IllegalStateException("database unavailable");
          });
    }

    // 瞬时故障也不能无限重试：达到上限后受控终止，让队列继续前进。
    assertThat(status).isEqualTo(ConsumeOrderlyStatus.SUCCESS);
  }

  @Test
  void serverSideFailureIsTreatedAsTransient() {
    assertThat(RemotingMqConsumerFactory.isUnrecoverable(new ApiException(500, "Internal error"))).isFalse();
    assertThat(RemotingMqConsumerFactory.isUnrecoverable(new ApiException(503, "Unavailable"))).isFalse();
  }

  @Test
  void retriableClientErrorsAreNotTreatedAsUnrecoverable() {
    // 这些 4xx 重试往往就能成功（时序窗口、限流、并发冲突），不能直接丢消息。
    assertThat(RemotingMqConsumerFactory.isUnrecoverable(new ApiException(404, "Not found"))).isFalse();
    assertThat(RemotingMqConsumerFactory.isUnrecoverable(new ApiException(408, "Timeout"))).isFalse();
    assertThat(RemotingMqConsumerFactory.isUnrecoverable(new ApiException(409, "Conflict"))).isFalse();
    assertThat(RemotingMqConsumerFactory.isUnrecoverable(new ApiException(429, "Too many requests"))).isFalse();
  }

  @Test
  void retriableClientErrorIsRetriedInsteadOfBeingDroppedImmediately() {
    ConsumeOrderlyStatus status = factory.consume(TOPIC, GROUP, List.of(message("flaky-404")), context(),
        body -> {
          throw new ApiException(404, "Not found");
        });

    // 404 属于可重试：第一次必须挂起重试，而不是像不可恢复错误那样立刻丢弃。
    assertThat(status).isEqualTo(ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT);
  }

  @Test
  void clientSideRejectionIsTreatedAsUnrecoverable() {
    assertThat(RemotingMqConsumerFactory.isUnrecoverable(new ApiException(400, "Bad request"))).isTrue();
    assertThat(RemotingMqConsumerFactory.isUnrecoverable(new ApiException(401, "Unauthorized"))).isTrue();
    assertThat(RemotingMqConsumerFactory.isUnrecoverable(new ApiException(403, "Not friends"))).isTrue();
    assertThat(RemotingMqConsumerFactory.isUnrecoverable(new ApiException(422, "Unprocessable"))).isTrue();
  }

  @Test
  void wrappedRejectionIsDetectedThroughCauseChain() {
    Exception wrapped = new IllegalStateException("mq handling failed", new ApiException(403, "Not friends"));

    assertThat(RemotingMqConsumerFactory.isUnrecoverable(wrapped)).isTrue();
  }

  private static MessageExt message(String body) {
    MessageExt message = new MessageExt();
    message.setTopic(TOPIC);
    message.setMsgId("msg-" + body);
    message.setKeys(body);
    message.setBody(body.getBytes(StandardCharsets.UTF_8));
    return message;
  }

  private static ConsumeOrderlyContext context() {
    return new ConsumeOrderlyContext(new MessageQueue(TOPIC, "broker-a", 0));
  }
}
