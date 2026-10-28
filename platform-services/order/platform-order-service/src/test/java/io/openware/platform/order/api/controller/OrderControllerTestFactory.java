package io.openware.platform.order.api.controller;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.application.DailySerialNumberGenerator;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderApplicationService;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.application.VoidRuleApplicationService;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.infra.client.ResourceStateClient;

/**
 * 测试装配：用旧的控制器构造签名创建 {@link OrderApplicationService}，再交给新的控制器。
 *
 * <p>生产装配只注入应用服务；这里保留旧的参数形状，避免每个控制器测试重复拼装服务。
 */
final class OrderControllerTestFactory {

  private OrderControllerTestFactory() {
  }

  static OrderController create(OrderRepository orderRepository,
                                KtvSessionApplicationService ktvSessionService,
                                KtvServerSessionApplicationService ktvServerSessionService,
                                ResourceStateClient resourceStateClient,
                                AuditClient auditClient,
                                OrderCancellationApplicationService orderCancellationService,
                                DailySerialNumberGenerator dailySerialNumberGenerator) {
    return create(orderRepository, ktvSessionService, ktvServerSessionService, resourceStateClient,
        auditClient, orderCancellationService, dailySerialNumberGenerator, null, null);
  }

  static OrderController create(OrderRepository orderRepository,
                                KtvSessionApplicationService ktvSessionService,
                                KtvServerSessionApplicationService ktvServerSessionService,
                                ResourceStateClient resourceStateClient,
                                AuditClient auditClient,
                                OrderCancellationApplicationService orderCancellationService,
                                DailySerialNumberGenerator dailySerialNumberGenerator,
                                MemberLookupProvider memberLookupProvider) {
    return create(orderRepository, ktvSessionService, ktvServerSessionService, resourceStateClient,
        auditClient, orderCancellationService, dailySerialNumberGenerator, memberLookupProvider, null);
  }

  static OrderController create(OrderRepository orderRepository,
                                KtvSessionApplicationService ktvSessionService,
                                KtvServerSessionApplicationService ktvServerSessionService,
                                ResourceStateClient resourceStateClient,
                                AuditClient auditClient,
                                OrderCancellationApplicationService orderCancellationService,
                                DailySerialNumberGenerator dailySerialNumberGenerator,
                                MemberLookupProvider memberLookupProvider,
                                VoidRuleApplicationService voidRuleService) {
    OrderApplicationService orderService = new OrderApplicationService(orderRepository, ktvSessionService,
        resourceStateClient, orderCancellationService, dailySerialNumberGenerator, memberLookupProvider);
    return new OrderController(orderService, ktvSessionService, ktvServerSessionService,
        orderCancellationService, voidRuleService);
  }
}
