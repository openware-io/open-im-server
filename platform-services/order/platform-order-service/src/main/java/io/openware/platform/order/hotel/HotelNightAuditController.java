package io.openware.platform.order.hotel;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/hotel/night-audit")
public class HotelNightAuditController {

    private final HotelNightAuditApplicationService service;

    public HotelNightAuditController(HotelNightAuditApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<HotelNightAuditPo> history() {
        return service.history();
    }

    @PostMapping("/run")
    public HotelNightAuditPo run(@RequestBody RunRequest request) {
        return service.run(request.businessDate());
    }

    public record RunRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {}
}
