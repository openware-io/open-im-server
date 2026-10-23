package io.openware.platform.order.hotel;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端酒店目录与报价；登录用户可读，租户隔离由应用服务强制。 */
@RestController
@RequestMapping("/business/hotel/catalog")
public class HotelPublicCatalogController {

    private final HotelPublicCatalogApplicationService service;

    public HotelPublicCatalogController(HotelPublicCatalogApplicationService service) {
        this.service = service;
    }

    @GetMapping("/hotels")
    public List<HotelPublicCatalogApplicationService.HotelSummary> hotels() {
        return service.hotels();
    }

    @GetMapping("/hotels/{id}")
    public HotelPublicCatalogApplicationService.HotelDetail detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping("/quotes")
    public HotelPublicCatalogApplicationService.PublicQuote quote(@RequestBody QuoteRequest request) {
        return service.quote(request.roomTypeId(), request.ratePlanId(), request.checkInDate(),
                request.checkOutDate(), request.rooms() == null ? 1 : request.rooms());
    }

    public record QuoteRequest(Long roomTypeId, Long ratePlanId,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInDate,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOutDate,
                               Integer rooms) {}
}
