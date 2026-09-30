package io.openware.protocol.mq.topic;

/** Customer 域事实事件主题；消费者以 eventId 做幂等。 */
public final class CustomerMqTopics {
  public static final String FACT_EVENT = "cst_fact_event_v1";

  private CustomerMqTopics() {
  }
}
