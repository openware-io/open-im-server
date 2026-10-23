package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** C 端酒店目录/报价：只读、按租户隔离，不要求门店上下文。 */
@Service
public class HotelPublicCatalogApplicationService {

    private final HotelMapper hotelMapper;
    private final HotelRoomTypeMapper roomTypeMapper;
    private final HotelRatePlanMapper ratePlanMapper;
    private final HotelDailyRateMapper dailyRateMapper;
    private final HotelInventoryMapper inventoryMapper;

    public HotelPublicCatalogApplicationService(HotelMapper hotelMapper, HotelRoomTypeMapper roomTypeMapper,
                                                HotelRatePlanMapper ratePlanMapper,
                                                HotelDailyRateMapper dailyRateMapper,
                                                HotelInventoryMapper inventoryMapper) {
        this.hotelMapper = hotelMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.ratePlanMapper = ratePlanMapper;
        this.dailyRateMapper = dailyRateMapper;
        this.inventoryMapper = inventoryMapper;
    }

    public List<HotelSummary> hotels() {
        TenantContext context = requireTenant();
        return hotelMapper.selectList(new LambdaQueryWrapper<HotelPo>()
                        .eq(HotelPo::getTenantId, context.tenantId())
                        .eq(HotelPo::getStatus, "ACTIVE")
                        .orderByAsc(HotelPo::getId))
                .stream()
                .map(hotel -> new HotelSummary(hotel.getId(), hotel.getStoreId(), hotel.getName(),
                        hotel.getAddress(), hotel.getTimezone()))
                .toList();
    }

    public HotelDetail detail(Long hotelId) {
        TenantContext context = requireTenant();
        HotelPo hotel = hotelMapper.selectById(hotelId);
        if (hotel == null || context.tenantId() != hotel.getTenantId()) {
            throw new ApiException(404, "HOTEL_NOT_FOUND", "酒店不存在");
        }
        List<RoomTypeView> roomTypes = roomTypeMapper.selectList(new LambdaQueryWrapper<HotelRoomTypePo>()
                        .eq(HotelRoomTypePo::getHotelId, hotelId)
                        .eq(HotelRoomTypePo::getStatus, "ACTIVE")
                        .orderByAsc(HotelRoomTypePo::getId))
                .stream()
                .map(type -> new RoomTypeView(type.getId(), type.getCode(), type.getName(), type.getCapacity(),
                        type.getBedType(), type.getAreaSqm()))
                .toList();
        List<RatePlanView> ratePlans = ratePlanMapper.selectList(new LambdaQueryWrapper<HotelRatePlanPo>()
                        .eq(HotelRatePlanPo::getHotelId, hotelId)
                        .eq(HotelRatePlanPo::getStatus, "ACTIVE")
                        .orderByAsc(HotelRatePlanPo::getId))
                .stream()
                .map(plan -> new RatePlanView(plan.getId(), plan.getCode(), plan.getName(),
                        plan.getBasePriceMinor(), plan.getCurrency()))
                .toList();
        return new HotelDetail(hotel.getId(), hotel.getStoreId(), hotel.getName(), hotel.getAddress(),
                hotel.getTimezone(), hotel.getCheckInTime(), hotel.getCheckOutTime(), roomTypes, ratePlans);
    }

    public PublicQuote quote(Long roomTypeId, Long ratePlanId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        requireTenant();
        if (roomTypeId == null || checkIn == null || checkOut == null || !checkOut.isAfter(checkIn) || rooms < 1) {
            throw new ApiException(400, "STAY_INVALID", "入住日期、离店日期或房间数不合法");
        }
        HotelRoomTypePo roomType = roomTypeMapper.selectById(roomTypeId);
        HotelRatePlanPo ratePlan = ratePlanId == null ? null : ratePlanMapper.selectById(ratePlanId);
        if (roomType == null || !"ACTIVE".equals(roomType.getStatus())) {
            throw new ApiException(404, "ROOM_TYPE_NOT_FOUND", "房型不存在");
        }
        if (ratePlan == null) {
            ratePlan = ratePlanMapper.selectOne(new LambdaQueryWrapper<HotelRatePlanPo>()
                    .eq(HotelRatePlanPo::getHotelId, roomType.getHotelId())
                    .eq(HotelRatePlanPo::getStatus, "ACTIVE")
                    .orderByAsc(HotelRatePlanPo::getId)
                    .last("LIMIT 1"));
        }
        if (ratePlan == null || !roomType.getHotelId().equals(ratePlan.getHotelId())) {
            throw new ApiException(404, "RATE_PLAN_NOT_FOUND", "房价计划不存在");
        }
        List<NightPrice> nights = new ArrayList<>();
        long total = 0;
        int available = Integer.MAX_VALUE;
        for (LocalDate date = checkIn; date.isBefore(checkOut); date = date.plusDays(1)) {
            HotelInventoryPo inventory = inventoryMapper.selectOne(new LambdaQueryWrapper<HotelInventoryPo>()
                    .eq(HotelInventoryPo::getRoomTypeId, roomTypeId)
                    .eq(HotelInventoryPo::getStayDate, date));
            int dayAvailable = inventory == null ? 0
                    : Math.max(0, inventory.getTotal() - inventory.getSold() - inventory.getHeld());
            available = Math.min(available, dayAvailable);
            HotelDailyRatePo daily = dailyRateMapper.selectOne(new LambdaQueryWrapper<HotelDailyRatePo>()
                    .eq(HotelDailyRatePo::getRoomTypeId, roomTypeId)
                    .eq(HotelDailyRatePo::getRatePlanId, ratePlan.getId())
                    .eq(HotelDailyRatePo::getStayDate, date));
            long price = daily == null ? ratePlan.getBasePriceMinor() : daily.getPriceMinor();
            nights.add(new NightPrice(date, price, dayAvailable));
            total = Math.addExact(total, Math.multiplyExact(price, rooms));
        }
        return new PublicQuote(available >= rooms, total, ratePlan.getCurrency(),
                (int) ChronoUnit.DAYS.between(checkIn, checkOut), nights);
    }

    private TenantContext requireTenant() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context;
    }

    public record HotelSummary(Long id, Long storeId, String name, String address, String timezone) {}

    public record RoomTypeView(Long id, String code, String name, Integer capacity, String bedType,
                               java.math.BigDecimal areaSqm) {}

    public record RatePlanView(Long id, String code, String name, Long basePriceMinor, String currency) {}

    public record HotelDetail(Long id, Long storeId, String name, String address, String timezone,
                              String checkInTime, String checkOutTime, List<RoomTypeView> roomTypes,
                              List<RatePlanView> ratePlans) {}

    public record NightPrice(LocalDate stayDate, long priceMinor, int available) {}

    public record PublicQuote(boolean available, long totalMinor, String currency, int nights,
                              List<NightPrice> nightPrices) {}
}
