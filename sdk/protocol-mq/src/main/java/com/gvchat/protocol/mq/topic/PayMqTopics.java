package com.gvchat.protocol.mq.topic;

public final class PayMqTopics {
  public static final String COLLECT_CONFIRMED_EVENT = "pay_collect_event_confirmed_v1";
  public static final String REFUND_REQUESTED_EVENT = "pay_refund_event_requested_v1";

  private PayMqTopics() {
  }
}
