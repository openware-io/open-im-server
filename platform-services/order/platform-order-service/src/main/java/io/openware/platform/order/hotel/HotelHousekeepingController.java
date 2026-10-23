package io.openware.platform.order.hotel;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.RestController;

@ClientAudience("saas-b")
@RestController
@RequestMapping("/admin/hotel/housekeeping")
public class HotelHousekeepingController {

    private final HotelHousekeepingApplicationService service;

    public HotelHousekeepingController(HotelHousekeepingApplicationService service) {
        this.service = service;
    }

    @GetMapping("/tasks")
    public List<HotelHousekeepingPo> tasks() {
        return service.tasks();
    }

    @PostMapping("/tasks/{id}/start")
    public HotelHousekeepingPo start(@PathVariable Long id) {
        return service.start(id);
    }

    @PostMapping("/tasks/{id}/complete")
    public HotelHousekeepingPo complete(@PathVariable Long id) {
        return service.complete(id);
    }
}
