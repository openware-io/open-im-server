package com.gvchat.protocol.mq.event;

public final class PayEventTypes {
  public static final String COLLECT_CONFIRMED = "payment.collect.confirmed";
  public static final String REFUND_REQUESTED = "payment.refund.requested";

  private PayEventTypes() {
  }
}
