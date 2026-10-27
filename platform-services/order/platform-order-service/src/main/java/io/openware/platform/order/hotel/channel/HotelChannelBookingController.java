package io.openware.platform.order.hotel.channel;

import io.openware.platform.order.hotel.channel.HotelChannelApi.AriItem;
import io.openware.platform.order.hotel.channel.HotelChannelApi.AriPushRequest;
import io.openware.platform.order.hotel.channel.HotelChannelApi.AriPushResponse;
import io.openware.platform.order.hotel.channel.HotelChannelApi.AriResult;
import io.openware.platform.order.hotel.channel.HotelChannelApi.BookingCreateRequest;
import io.openware.platform.order.hotel.channel.HotelChannelApi.BookingResponse;
import io.openware.platform.order.hotel.channel.HotelChannelApi.CancelRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 酒店 OTA channel v1 对外入口。
 *
 * <p>路径固定为 {@code /api/v1/open/hotel/v1/**}（网关不剥前缀，下游 requestURI 与外部路径一致，
 * HMAC canonical path 不会因网关转发而漂移）。鉴权由 {@link HotelChannelAuthenticationFilter} 在
 * 过滤器层强制完成，本控制器不提供任何未鉴权分支。
 */
@RestController
@RequestMapping("/api/v1/open/hotel")
public class HotelChannelBookingController {

    private final HotelChannelBookingApplicationService service;

    public HotelChannelBookingController(HotelChannelBookingApplicationService service) {
        this.service = service;
    }

    /** 创建：新建 201；同 externalRef 同内容回放 200；内容不一致 409。 */
    @PostMapping("/v1/bookings")
    public ResponseEntity<BookingResponse> create(HttpServletRequest request,
                                                  @RequestBody BookingCreateRequest body) {
        String channelCode = (String) request.getAttribute(HotelChannelAuthenticationFilter.CHANNEL_ATTRIBUTE);
        try {
            var result = service.create(channelCode, body);
            return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(result.response());
        } catch (HotelChannelBookingApplicationService.ChannelBookingConflictException conflict) {
            // 唯一键冲突：让失败事务整体回滚后按 externalRef 回放已有预订。
            return ResponseEntity.ok(service.replayAfterConflict(channelCode, body));
        }
    }

    /** 查询：不存在 404。 */
    @GetMapping("/v1/bookings/{externalRef}")
    public BookingResponse get(HttpServletRequest request, @PathVariable String externalRef) {
        return service.get(channelCode(request), externalRef);
    }

    /** 取消：幂等，已取消返回 200；状态机不允许取消时 409。 */
    @PostMapping("/v1/bookings/{externalRef}/cancel")
    public BookingResponse cancel(HttpServletRequest request, @PathVariable String externalRef,
                                  @RequestBody(required = false) CancelRequest body) {
        return service.cancel(channelCode(request), externalRef, body == null ? null : body.reason());
    }

    /**
     * ARI 推送：v1 只保留契约占位，返回 202 no-op 且逐项 accepted=false。
     * 文档（hotel-channel-v1.yaml）明确标注未实现，避免渠道把 202 误当成已落库。
     */
    @PostMapping("/v1/ari")
    public ResponseEntity<AriPushResponse> ari(@RequestBody AriPushRequest body) {
        List<AriResult> results = new ArrayList<>();
        if (body != null && body.items() != null) {
            for (AriItem item : body.items()) {
                results.add(new AriResult(item == null ? null : item.stayDate(), false,
                        "ARI_NOT_IMPLEMENTED", "ARI push is not implemented in channel v1; no data was written"));
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AriPushResponse(results));
    }

    private static String channelCode(HttpServletRequest request) {
        return (String) request.getAttribute(HotelChannelAuthenticationFilter.CHANNEL_ATTRIBUTE);
    }
}
