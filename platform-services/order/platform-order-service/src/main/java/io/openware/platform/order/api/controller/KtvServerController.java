package io.openware.platform.order.api.controller;

import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.infra.persistence.po.KtvServerSessionPo;
import io.openware.infrastructure.tenant.PermissionGuard;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务人员点单结束/取消（点单入口在 {@link OrderController#orderServer}，路径 /business/orders/{id}/servers）。
 */
@RestController
@RequestMapping("/business/ktv/servers")
public class KtvServerController {
    private final KtvServerSessionApplicationService serverSessionService;

    public KtvServerController(KtvServerSessionApplicationService serverSessionService) {
        this.serverSessionService = serverSessionService;
    }

    /** 结束服务：POST /business/ktv/servers/{id}/end → ENDED（固化时长/金额）。 */
    @PostMapping("/{id}/end")
    public KtvServerSessionPo end(@PathVariable Long id) {
        PermissionGuard.require("ktv.server.end");
        return serverSessionService.end(id);
    }

    /** 取消点单：POST /business/ktv/servers/{id}/cancel → CANCELLED（仅 ORDERED 且未计费）。 */
    @PostMapping("/{id}/cancel")
    public KtvServerSessionPo cancel(@PathVariable Long id) {
        PermissionGuard.require("ktv.server.order");
        return serverSessionService.cancel(id);
    }
}
