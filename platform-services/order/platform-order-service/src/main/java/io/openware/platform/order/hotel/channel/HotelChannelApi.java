package io.openware.platform.order.hotel.channel;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 酒店 OTA channel v1 对外 DTO。
 *
 * <p>请求里没有 tenant/organization/store/business 字段：这些只能由渠道凭证 + 门店权威数据在服务端
 * 解析，避免外部请求把酒店数据写进错的组织/业态。
 */
public final class HotelChannelApi {

    private HotelChannelApi() {
    }

    /** 创建 OTA 预订；{@code externalRef} 即渠道侧幂等键。 */
    public record BookingCreateRequest(
            String externalRef,
            String hotelRef,
            String roomTypeRef,
            String ratePlanRef,
            LocalDate checkIn,
            LocalDate checkOut,
            Integer rooms,
            Integer guests,
            Guest guest,
            Long amountMinor,
            String currency,
            String paymentResponsibility) {
    }

    /** 住客信息；酒店预订创建要求姓名与手机号。 */
    public record Guest(String name, String phone) {
    }

    /** 取消请求体可选。 */
    public record CancelRequest(String reason) {
    }

    /** 渠道可见的预订状态（数值/金额均为最小货币单位）。 */
    public record BookingResponse(
            String bookingNo,
            String externalRef,
            String status,
            String hotelRef,
            LocalDate checkIn,
            LocalDate checkOut,
            Integer rooms,
            String currency,
            Long amountMinor,
            Long refundMinor,
            Long cancelFeeMinor,
            LocalDateTime cancelledAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** ARI 推送（v1 未实现，接口返回 202 no-op，逐项 accepted=false）。 */
    public record AriPushRequest(List<AriItem> items) {
    }

    /** 单条 ARI 数据。 */
    public record AriItem(
            String hotelRef,
            String roomTypeRef,
            String ratePlanRef,
            LocalDate stayDate,
            Integer total,
            Integer sold,
            Boolean stopSell,
            Long priceMinor,
            String currency,
            Integer minStay,
            Integer version) {
    }

    /** ARI 单项结果。 */
    public record AriResult(LocalDate stayDate, boolean accepted, String code, String message) {
    }

    /** ARI 推送响应（202 no-op）。 */
    public record AriPushResponse(List<AriResult> results) {
    }
}
