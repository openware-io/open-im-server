package io.openware.platform.order.hotel;

import io.openware.infrastructure.tenant.ClientAudience;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 房态图、清扫状态、维修/停售与客房保留。 */
@ClientAudience("saas-b")
@RestController
@RequestMapping("/admin/hotel/rooms")
public class HotelRoomStateController {

    private final HotelRoomStateApplicationService service;
    private final HotelStoreScopeResolver hotelStoreScope;

    public HotelRoomStateController(HotelRoomStateApplicationService service,
                                    HotelStoreScopeResolver hotelStoreScope) {
        this.service = service;
        this.hotelStoreScope = hotelStoreScope;
    }

    /** 房态图：三组房态字段 + 当前在住客人；展示态由前端派生。 */
    @GetMapping("/board")
    public List<HotelRoomBoardView> board() {
        // 读路径同样校验门店业态：KTV 门店不能通过直连 API 读取酒店房态。
        hotelStoreScope.requireHotelReadScope(TenantContextHolder.get());
        return service.board();
    }

    @PutMapping("/{id}/housekeeping")
    public HotelRoomPo setHousekeeping(@PathVariable Long id, @RequestBody HousekeepingRequest request) {
        return service.setHousekeepingStatus(id, request.status());
    }

    /** 维修/停售/恢复：会按日期同步调整房型房量。 */
    @PutMapping("/{id}/out-of-order")
    public HotelRoomPo setOutOfOrder(@PathVariable Long id, @RequestBody OutOfOrderRequest request) {
        return service.setOutOfOrder(id, request.status(), request.from(), request.to());
    }

    @GetMapping("/{id}/holds")
    public List<HotelRoomHoldPo> holds(@PathVariable Long id) {
        return service.holds(id);
    }

    @PostMapping("/{id}/holds")
    public HotelRoomHoldPo addHold(@PathVariable Long id, @RequestBody HoldRequest request) {
        return service.addHold(id, request.reason(), request.bookingId(), request.from(), request.to());
    }

    @PostMapping("/{id}/holds/{holdId}/release")
    public HotelRoomHoldPo releaseHold(@PathVariable Long id, @PathVariable Long holdId) {
        return service.releaseHold(id, holdId);
    }

    public record HousekeepingRequest(String status) {}

    public record OutOfOrderRequest(String status, String reason, LocalDate from, LocalDate to) {}

    public record HoldRequest(String reason, Long bookingId, LocalDate from, LocalDate to) {}
}
