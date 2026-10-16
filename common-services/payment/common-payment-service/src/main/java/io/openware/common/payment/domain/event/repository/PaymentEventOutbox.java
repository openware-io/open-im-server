package io.openware.common.payment.domain.event.repository;

import io.openware.common.payment.domain.event.model.PaymentEventOutboxEntry;

/**
 * 领域事件出站记录端口（Outbox 追加）。
 *
 * <p>投递属基础设施（standards/10“领域事件与可靠发布”：MQ 投递只能由 Relay 在事务提交后异步执行）；
 * 领域与应用层只负责**在同一事务内**追加一条待投递记录，因此本端口只有追加、
 * 没有查询或状态推进——那两件事属于 Relay。
 */
public interface PaymentEventOutbox {

  /**
   * 追加一条待投递事件，返回是否确实落库。
   *
   * <p>由实现补齐状态（{@code PENDING}）与重试计数初值、以及审计字段：
   * 调用方只表达"这条事件要发出去"，不重复陈述列的默认值。
   */
  boolean append(PaymentEventOutboxEntry entry);
}
