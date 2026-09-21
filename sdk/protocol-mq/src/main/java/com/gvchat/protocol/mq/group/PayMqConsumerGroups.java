package com.gvchat.protocol.mq.group;

public final class PayMqConsumerGroups {
  public static final String COMMON_PAYMENT_SERVICE_COLLECT_CONFIRMED =
      "common-payment-service-collect-confirmed-consumer";
  public static final String COMMON_PAYMENT_SERVICE_REFUND_REQUESTED =
      "common-payment-service-refund-requested-consumer";

  private PayMqConsumerGroups() {
  }
}
