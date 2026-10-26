package io.openware.platform.order.hotel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.RestController;

/** 酒店主数据 API；权限和门店上下文由应用服务强制校验。 */
@ClientAudience("saas-b")
@RestController
@RequestMapping("/admin/hotel")
public class HotelMasterDataController {

    private final HotelMasterDataApplicationService service;

    public HotelMasterDataController(HotelMasterDataApplicationService service) {
        this.service = service;
    }

    @GetMapping("/hotels")
    public List<HotelPo> hotels() {
        return service.hotels();
    }

    @PostMapping("/hotels")
    @ResponseStatus(HttpStatus.CREATED)
    public HotelPo createHotel(@RequestBody HotelRequest request) {
        return service.saveHotel(null, request.name(), request.address(), request.coverImageUrl(), request.timezone(),
                request.checkInTime(), request.checkOutTime());
    }

    @PutMapping("/hotels/{id}")
    public HotelPo updateHotel(@PathVariable Long id,
                               @RequestBody HotelRequest request) {
        return service.saveHotel(id, request.name(), request.address(), request.coverImageUrl(), request.timezone(),
                request.checkInTime(), request.checkOutTime());
    }

    @GetMapping("/room-types")
    public List<HotelRoomTypePo> roomTypes(@RequestParam(required = false) Long hotelId) {
        return service.roomTypes(hotelId);
    }

    @PostMapping("/room-types")
    @ResponseStatus(HttpStatus.CREATED)
    public HotelRoomTypePo createRoomType(@RequestBody RoomTypeRequest request) {
        return service.saveRoomType(null, request.hotelId(), request.code(), request.name(), request.capacity(),
                request.bedType(), request.areaSqm());
    }

    @PutMapping("/room-types/{id}")
    public HotelRoomTypePo updateRoomType(@PathVariable Long id,
                                          @RequestBody RoomTypeRequest request) {
        return service.saveRoomType(id, request.hotelId(), request.code(), request.name(), request.capacity(),
                request.bedType(), request.areaSqm());
    }

    @GetMapping("/rooms")
    public List<HotelRoomPo> rooms(@RequestParam(required = false) Long roomTypeId) {
        return service.rooms(roomTypeId);
    }

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public HotelRoomPo createRoom(@RequestBody RoomRequest request) {
        return service.saveRoom(null, request.roomTypeId(), request.roomNo(), request.floor());
    }

    @PutMapping("/rooms/{id}")
    public HotelRoomPo updateRoom(@PathVariable Long id,
                                  @RequestBody RoomRequest request) {
        return service.saveRoom(id, request.roomTypeId(), request.roomNo(), request.floor());
    }

    @PutMapping("/rooms/{id}/status")
    public HotelRoomPo setRoomStatus(@PathVariable Long id, @RequestBody RoomStatusRequest request) {
        return service.setRoomStatus(id, request.status());
    }

    @GetMapping("/rate-plans")
    public List<HotelRatePlanPo> ratePlans(@RequestParam(required = false) Long hotelId) {
        return service.ratePlans(hotelId);
    }

    @PostMapping("/rate-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public HotelRatePlanPo createRatePlan(@RequestBody RatePlanRequest request) {
        return service.saveRatePlan(null, request.hotelId(), request.code(), request.name(),
                request.basePriceMinor(), request.currency());
    }

    @PutMapping("/rate-plans/{id}")
    public HotelRatePlanPo updateRatePlan(@PathVariable Long id,
                                          @RequestBody RatePlanRequest request) {
        return service.saveRatePlan(id, request.hotelId(), request.code(), request.name(),
                request.basePriceMinor(), request.currency());
    }

    @GetMapping("/daily-rates")
    public List<HotelDailyRatePo> dailyRates(@RequestParam Long roomTypeId, @RequestParam Long ratePlanId,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.dailyRates(roomTypeId, ratePlanId, from, to);
    }

    @PutMapping("/daily-rates")
    public HotelDailyRatePo setDailyRate(@RequestBody DailyRateRequest request) {
        return service.setDailyRate(request.roomTypeId(), request.ratePlanId(), request.stayDate(),
                request.priceMinor(), request.currency());
    }

    @GetMapping("/inventory")
    public List<HotelInventoryPo> inventory(@RequestParam Long roomTypeId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.inventory(roomTypeId, from, to);
    }

    @PutMapping("/inventory")
    public HotelInventoryPo setInventory(@RequestBody InventoryRequest request) {
        return service.setInventory(request.roomTypeId(), request.stayDate(), request.total());
    }

    public record HotelRequest(String name, String address, String coverImageUrl, String timezone, String checkInTime, String checkOutTime) {}

    public record RoomTypeRequest(Long hotelId, String code, String name, Integer capacity, String bedType,
                                  BigDecimal areaSqm) {}

    public record RoomRequest(Long roomTypeId, String roomNo, String floor) {}

    public record RoomStatusRequest(String status) {}

    public record RatePlanRequest(Long hotelId, String code, String name, Long basePriceMinor, String currency) {}

    public record InventoryRequest(Long roomTypeId, LocalDate stayDate, Integer total) {}

    public record DailyRateRequest(Long roomTypeId, Long ratePlanId, LocalDate stayDate, Long priceMinor,
                                   String currency) {}
}
