package io.openware.platform.order.hotel;

import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 酒店店铺策略 API；支付模式/押金策略/取消与积分开关。 */
@ClientAudience("saas-b")
@RestController
@RequestMapping("/admin/hotel/policy")
public class HotelPolicyController {

    private final HotelPolicyApplicationService service;

    public HotelPolicyController(HotelPolicyApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public HotelPolicyPo get(@RequestParam Long hotelId) {
        return service.current(hotelId);
    }

    @PutMapping
    public HotelPolicyPo save(@RequestParam Long hotelId, @RequestBody HotelPolicyRequest request) {
        return service.save(hotelId, request.accessMode(), request.entryPoints(), request.paymentMode(),
                request.depositRatioBps(), request.stayDepositRequired(), request.stayDepositMode(),
                request.stayDepositMinor(), request.stayDepositRatioBps(), request.cancelPolicy(),
                request.pointsRedeemEnabled(), request.pointsMaxRatioBps());
    }

    public record HotelPolicyRequest(String accessMode, String entryPoints, String paymentMode,
                                     Integer depositRatioBps, Boolean stayDepositRequired,
                                     String stayDepositMode, Long stayDepositMinor,
                                     Integer stayDepositRatioBps, String cancelPolicy,
                                     Boolean pointsRedeemEnabled, Integer pointsMaxRatioBps) {
    }
}
