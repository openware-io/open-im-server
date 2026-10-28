package io.openware.platform.order.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.ClientAudience;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.ReservationView;
import io.openware.platform.order.application.OrderQueryApplicationService;
import io.openware.platform.order.application.ReservationApplicationService;
import io.openware.platform.order.application.ReservationApplicationService.CreateReservationCommand;
import io.openware.platform.order.application.dto.OrderView;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer order history and consumer-side reservation entry.
 *
 * <p>Queries are constrained by the authenticated account's member ID. The C-side
 * reservation create is deliberately exposed here (not on the B-side
 * {@code /business/reservations} controller): it derives tenant/store/business from
 * the signed consumer context and the customer ID from the membership, so a consumer
 * cannot choose another store or act as a back-office operator.
 *
 * <p>会员归属与订单查询在 {@link OrderQueryApplicationService}；预约展示装配在
 * {@link ReservationApplicationService}。本类只做协议转换与权限/上下文收窄。
 */
@RestController
@RequestMapping("/me")
public class MyOrderController {
  private final OrderQueryApplicationService orderQueryService;
  private final ReservationApplicationService reservationService;

  public MyOrderController(OrderQueryApplicationService orderQueryService,
                           ReservationApplicationService reservationService) {
    this.orderQueryService = orderQueryService;
    this.reservationService = reservationService;
  }

  /** 我的订单（C 端）：按会员收窄，创建时间倒序。 */
  @GetMapping("/orders")
  public List<OrderView> orders() {
    return orderQueryService.listMine().stream().map(OrderView::from).toList();
  }

  /**
   * 我的预约（C 端）：预约按房型创建，这里回填房型名/编码与已分配包厢名，
   * 前端展示「包厢类型（房型）+ 到店后由门店分配包厢」；历史预约（只有包厢）回退显示旧包厢名。
   */
  @GetMapping("/reservations")
  public List<ReservationView> reservations() {
    return reservationService.listMine(orderQueryService.currentMemberId());
  }

  /**
   * C 端创建 KTV 预约：租户/门店/业态全部取签名消费者上下文，customerId 取当前会员；
   * 请求体只允许选择房型、时段、人数和联系人，禁止越权指定门店/客户。
   */
  @ClientAudience("saas-c")
  @PostMapping("/reservations")
  public ReservationView createReservation(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CReservationRequest request) {
    PermissionGuard.require("reservation.create");
    TenantContext context = TenantContextHolder.get();
    if (context == null || context.tenantId() <= 0 || context.storeId() == null) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少门店上下文");
    }
    if (request == null || request.roomTypeId() == null) {
      throw new ApiException(400, "ROOM_TYPE_REQUIRED",
          "缺少 roomTypeId：预约按房型（包厢类型）创建，具体包厢到店后由门店分配");
    }
    Long customerId = orderQueryService.currentMemberId();
    if (customerId == null) {
      throw new ApiException(409, "MEMBER_INITIALIZATION_REQUIRED", "请先初始化会员资料");
    }
    String businessType = context.businessType();
    if (businessType == null || businessType.isBlank()) {
      // /me/reservations is the KTV consumer reservation entry; older consumer
      // context tokens did not carry a businessType claim.
      businessType = "KTV";
    }
    CreateReservationCommand command = new CreateReservationCommand(
        businessType, customerId, context.storeId(), request.roomTypeId(),
        request.startAt(), request.endAt(), request.partySize(), request.contact());
    return reservationService.create(context.tenantId(), idempotencyKey, command);
  }

  /** C 端预约创建请求：门店/业态/客户由服务端上下文派生。 */
  public record CReservationRequest(
      Long roomTypeId,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      Integer partySize,
      String contact) {
  }
}
