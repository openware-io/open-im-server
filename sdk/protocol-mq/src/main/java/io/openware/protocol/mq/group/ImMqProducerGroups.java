package io.openware.protocol.mq.group;

public final class ImMqProducerGroups {
  public static final String MESSAGE_SERVICE = "im-message-service-producer";
  public static final String ACCESS_WS = "im-access-ws-producer";
  public static final String USER_SERVICE = "im-user-service-producer";
  /** 会话服务经自己的 Outbox（ConversationAuthorizationOutboxRelay）发布授权变更事件。 */
  public static final String CONVERSATION_SERVICE = "im-conversation-service-producer";
  /**
   * 以下两个 group 由共享的 {@code MqProducer} Bean 注册（{@code MqInfrastructureConfig#mqProducer}
   * 对每个启用 MQ 的服务都会创建，且 {@code mqConsumerFactory} 依赖它）。这两个服务自身**不发布**
   * 任何消息，只是消费，所以它们的 producer group 虽会被 RocketMQ 注册，但从不发消息。
   * 这里登记常量是为了让配置中的资源名只有一个定义处（config 里写的是
   * {@code ${VAR:值}}，常量与值必须一致）；**并不表示这两个服务是事件生产者**。
   */
  public static final String ADMIN_SERVICE = "im-admin-service-producer";
  public static final String IDENTITY_SERVICE = "platform-identity-service-producer";

  private ImMqProducerGroups() {
  }
}
