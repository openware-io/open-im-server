package io.openware.platform.order.api.controller;

import io.openware.platform.order.api.dto.KtvServerSessionView;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.PathVariable;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.PostMapping;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.RequestMapping;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务人员点单结束/取消（点单入口在 {@link OrderController#orderServer}，路径 /business/orders/{id}/servers）。
 */
@ClientAudience("saas-b")
@RestController
@RequestMapping("/business/ktv/servers")
public class KtvServerController {
    private final KtvServerSessionApplicationService serverSessionService;

    public KtvServerController(KtvServerSessionApplicationService serverSessionService) {
        this.serverSessionService = serverSessionService;
    }

    /** 结束服务：POST /business/ktv/servers/{id}/end → ENDED（固化时长/金额）。 */
    @PostMapping("/{id}/end")
    public KtvServerSessionView end(@PathVariable Long id) {
        PermissionGuard.require("ktv.server.end");
        return KtvServerSessionView.from(serverSessionService.end(id));
    }

    /** 取消点单：POST /business/ktv/servers/{id}/cancel → CANCELLED（仅 ORDERED 且未计费）。 */
    @PostMapping("/{id}/cancel")
    public KtvServerSessionView cancel(@PathVariable Long id) {
        PermissionGuard.require("ktv.server.order");
        return KtvServerSessionView.from(serverSessionService.cancel(id));
    }
}
