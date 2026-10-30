package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.OrderApplicationService;
import io.openware.platform.order.domain.inventory.repository.StoreScopeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 酒店报价、房量占用、预订创建/确认/取消。房量扣减全部走原子 UPDATE。 */
@Service
public class HotelBookingApplicationService {

    private static final int HOLD_MINUTES = 15;

    private final HotelBookingMapper bookingMapper;
    private final HotelRoomTypeMapper roomTypeMapper;
    private final HotelRatePlanMapper ratePlanMapper;
    private final HotelRoomAvailabilityMapper availabilityMapper;
    private final HotelRoomAvailabilityHoldMapper availabilityHoldMapper;
    private final HotelDailyRateMapper dailyRateMapper;
    private final HotelBookingNightMapper nightMapper;
    private final OrderApplicationService orderApplicationService;
    private final MemberLookupProvider memberLookupProvider;
    private final HotelPolicyApplicationService policyService;
    private final HotelStoreScopeResolver storeScopeResolver;
    private final HotelPointsClient pointsClient;

    @Autowired
    public HotelBookingApplicationService(HotelBookingMapper bookingMapper, HotelRoomTypeMapper roomTypeMapper,
                                          HotelRatePlanMapper ratePlanMapper,
                                          HotelRoomAvailabilityMapper availabilityMapper,
                                          HotelRoomAvailabilityHoldMapper availabilityHoldMapper,
                                          HotelDailyRateMapper dailyRateMapper,
                                          HotelBookingNightMapper nightMapper,
                                          OrderApplicationService orderApplicationService,
                                          MemberLookupProvider memberLookupProvider,
                                          HotelPolicyApplicationService policyService,
                                          HotelStoreScopeResolver storeScopeResolver,
                                          HotelPointsClient pointsClient) {
        this.bookingMapper = bookingMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.ratePlanMapper = ratePlanMapper;
        this.availabilityMapper = availabilityMapper;
        this.availabilityHoldMapper = availabilityHoldMapper;
        this.dailyRateMapper = dailyRateMapper;
        this.nightMapper = nightMapper;
        this.orderApplicationService = orderApplicationService;
        this.memberLookupProvider = memberLookupProvider;
        this.policyService = policyService;
        this.storeScopeResolver = storeScopeResolver;
        this.pointsClient = pointsClient;
    }

    public HotelBookingApplicationService(HotelBookingMapper bookingMapper, HotelRoomTypeMapper roomTypeMapper,
                                              HotelRatePlanMapper ratePlanMapper,
                                              HotelRoomAvailabilityMapper availabilityMapper,
                                              HotelRoomAvailabilityHoldMapper availabilityHoldMapper,
                                              HotelDailyRateMapper dailyRateMapper,
                                              HotelBookingNightMapper nightMapper,
                                              OrderApplicationService orderApplicationService,
                                              MemberLookupProvider memberLookupProvider,
                                              HotelPolicyApplicationService policyService,
                                              HotelStoreScopeResolver storeScopeResolver) {
        this(bookingMapper, roomTypeMapper, ratePlanMapper, availabilityMapper, availabilityHoldMapper,
                dailyRateMapper, nightMapper, orderApplicationService, memberLookupProvider, policyService,
                storeScopeResolver, null);
    }

    public Quote quote(Long roomTypeId, Long ratePlanId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        validateStay(checkIn, checkOut, rooms);
        HotelRoomTypePo roomType = requireRoomType(roomTypeId, context);
        HotelRatePlanPo ratePlan = requireRatePlan(ratePlanId, roomType.getHotelId(), context);
        List<NightQuote> nights = new ArrayList<>();
        long total = 0;
        int available = Integer.MAX_VALUE;
        for (LocalDate date = checkIn; date.isBefore(checkOut); date = date.plusDays(1)) {
            HotelRoomAvailabilityPo availability = availability(context, roomTypeId, date);
            int dayAvailable = availability == null ? 0
                    : Math.max(0, availability.getTotal() - availability.getSold() - availability.getHeld());
            available = Math.min(available, dayAvailable);
            long price = dailyPrice(context, roomTypeId, ratePlan, date);
            nights.add(new NightQuote(date, price, dayAvailable));
            total = Math.addExact(total, Math.multiplyExact(price, rooms));
        }
        return new Quote(available >= rooms, total, ratePlan.getCurrency(), nights,
                LocalDateTime.now().plusMinutes(HOLD_MINUTES));
    }

    /**
     * C 端报价：消费者上下文所属门店可能不是酒店（同一租户多业态），
     * 房型自身携带权威 storeId，因此以房型门店执行酒店读作用域，避免 C 端
     * 只能绑定一个业态门店时无法预订同租户其它酒店。
     */
    public Quote quoteForCustomer(Long roomTypeId, Long ratePlanId, LocalDate checkIn, LocalDate checkOut,
                                  int rooms) {
        return inRoomTypeStore(roomTypeId, () -> quote(roomTypeId, ratePlanId, checkIn, checkOut, rooms));
    }

    @Transactional
    public HotelBookingPo createBooking(Long roomTypeId, Long ratePlanId, LocalDate checkIn, LocalDate checkOut,
                                        int rooms, String guestName, String guestPhone, String note) {
        return createBooking(roomTypeId, ratePlanId, checkIn, checkOut, rooms, guestName, guestPhone, note, null, 0L);
    }

    @Transactional
    public HotelBookingPo createBooking(Long roomTypeId, Long ratePlanId, LocalDate checkIn, LocalDate checkOut,
                                        int rooms, String guestName, String guestPhone, String note,
                                        Long bookedByAccountId) {
        return createBooking(roomTypeId, ratePlanId, checkIn, checkOut, rooms, guestName, guestPhone, note,
                bookedByAccountId, 0L);
    }

    @Transactional
    public HotelBookingPo createBooking(Long roomTypeId, Long ratePlanId, LocalDate checkIn, LocalDate checkOut,
                                        int rooms, String guestName, String guestPhone, String note,
                                        Long bookedByAccountId, Long requestedPoints) {
        return createBooking(roomTypeId, ratePlanId, checkIn, checkOut, rooms, guestName, guestPhone, note,
                bookedByAccountId, requestedPoints, "WEB");
    }

    /** C 端预订：与 {@link #quoteForCustomer} 相同，按房型权威门店执行酒店写作用域。 */
    @Transactional
    public HotelBookingPo createBookingForCustomer(Long roomTypeId, Long ratePlanId, LocalDate checkIn,
                                                   LocalDate checkOut, int rooms, String guestName,
                                                   String guestPhone, String note, Long bookedByAccountId,
                                                   Long requestedPoints) {
        return inRoomTypeStore(roomTypeId, () -> createBooking(roomTypeId, ratePlanId, checkIn, checkOut,
                rooms, guestName, guestPhone, note, bookedByAccountId, requestedPoints, "WEB"));
    }

    /**
     * 同一创建用例的渠道来源重载：OTA 渠道把 {@code source} 记为 {@code OTA_<channel>}，
     * 其它创建入口仍默认 WEB。来源只影响快照字段，不改变房量/订单/权限逻辑。
     */
    @Transactional
    public HotelBookingPo createBooking(Long roomTypeId, Long ratePlanId, LocalDate checkIn, LocalDate checkOut,
                                        int rooms, String guestName, String guestPhone, String note,
                                        Long bookedByAccountId, Long requestedPoints, String source) {
        PermissionGuard.require("hotel.booking.create");
        TenantContext context = requireStore();
        StoreScopeRepository.StoreScope storeScope = storeScopeResolver.requireHotelScope(context);
        if (guestName == null || guestName.isBlank() || guestPhone == null || guestPhone.isBlank()) {
            throw new ApiException(400, "GUEST_REQUIRED", "住客姓名和手机号不能为空");
        }
        if (guestName.trim().matches("^[?\\s]+$")) {
            throw new ApiException(400, "GUEST_NAME_INVALID", "住客姓名包含无效编码，请重新输入");
        }
        // 非 UTF-8 客户端把中文替换成 0x3F 后会形成 "????C?" 这类姓名；不能只拦截“全问号”。
        if (guestName.contains("?") || guestName.contains("\uFFFD")) {
            throw new ApiException(400, "GUEST_NAME_INVALID", "住客姓名包含无效问号或替换字符，请使用 UTF-8 重新输入");
        }
        Quote quote = quote(roomTypeId, ratePlanId, checkIn, checkOut, rooms);
        if (!quote.available()) {
            throw new ApiException(409, "ROOM_AVAILABILITY_UNAVAILABLE", "所选日期房量不足");
        }
        HotelRoomTypePo roomType = requireRoomType(roomTypeId, context);
        HotelRatePlanPo ratePlan = requireRatePlan(ratePlanId, roomType.getHotelId(), context);
        HotelPolicyPo paymentPolicy = policyService.paymentPolicy(roomType.getHotelId(), context);
        long pointsUsed = requestedPoints == null ? 0L : requestedPoints;
        if (pointsUsed < 0) {
            throw new ApiException(400, "HOTEL_POINTS_INVALID", "积分数量不能为负数");
        }
        Long customerId = bookedByAccountId == null ? null
                : memberLookupProvider.findMemberId(context.tenantId(), bookedByAccountId).orElse(null);
        // C 端订单必须在 customer 域有会员档案，否则 ord_order.customer_id 为空，
        // “我的消费”按 memberId 查询会永久漏掉这笔酒店订单。
        if (bookedByAccountId != null && customerId == null) {
            throw new ApiException(409, "MEMBER_INITIALIZATION_REQUIRED", "请先初始化会员资料");
        }
        long pointsDiscount = 0L;
        if (pointsUsed > 0) {
            if (!Boolean.TRUE.equals(paymentPolicy.getPointsRedeemEnabled())
                    || paymentPolicy.getPointsMaxRatioBps() == null
                    || paymentPolicy.getPointsMaxRatioBps() <= 0) {
                throw new ApiException(400, "HOTEL_POINTS_DISABLED", "当前酒店未开启积分抵扣");
            }
            if (customerId == null) {
                throw new ApiException(403, "MEMBER_NOT_FOUND", "当前账号没有会员档案，不能使用积分");
            }
            long maxDiscount = Math.round(quote.totalMinor()
                    * paymentPolicy.getPointsMaxRatioBps() / 10_000.0);
            pointsDiscount = pointsClient.amountForPoints(pointsUsed);
            if (pointsDiscount <= 0 || pointsDiscount > maxDiscount) {
                throw new ApiException(400, "HOTEL_POINTS_LIMIT_EXCEEDED", "积分抵扣超过本单上限");
            }
            if (pointsClient.balance(customerId) < pointsUsed) {
                throw new ApiException(409, "HOTEL_POINTS_INSUFFICIENT", "会员积分余额不足");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        HotelBookingPo booking = new HotelBookingPo();
        booking.setTenantId(context.tenantId());
        booking.setOrganizationId(storeScope.organizationId());
        booking.setBusinessType(storeScope.businessType());
        booking.setStoreId(context.storeId());
        booking.setHotelId(roomType.getHotelId());
        booking.setRoomTypeId(roomTypeId);
        booking.setRatePlanId(ratePlanId);
        booking.setBookingNo("HTL" + System.currentTimeMillis()
                + ThreadLocalRandom.current().nextInt(100, 999));
        booking.setGuestName(guestName.trim());
        booking.setGuestPhone(guestPhone);
        booking.setRoomType(roomType.getName());
        booking.setRooms(rooms);
        booking.setNights((int) ChronoUnit.DAYS.between(checkIn, checkOut));
        booking.setCheckInDate(checkIn);
        booking.setCheckOutDate(checkOut);
        booking.setStatus("PENDING_CONFIRM");
        booking.setAmountMinor(quote.totalMinor() - pointsDiscount);
        booking.setPointsUsed(pointsUsed);
        booking.setPointsDiscountMinor(pointsDiscount);
        booking.setCurrency(quote.currency());
        booking.setSource(normalizeSource(source));
        booking.setHoldExpiresAt(now.plusMinutes(HOLD_MINUTES));
        booking.setVersion(0);
        booking.setCreatedAt(now);
        booking.setUpdatedAt(now);
        bookingMapper.insert(booking);

        String pointsKey = "hotel-points:" + booking.getBookingNo();
        boolean pointsRedeemed = false;
        try {
            if (pointsUsed > 0) {
                pointsClient.redeem(customerId, pointsUsed, booking.getId(), pointsKey);
                pointsRedeemed = true;
            }
            booking.setOrderId(orderApplicationService.createHotelReceivable(context.tenantId(),
                    context.organizationId(), context.storeId(), booking.getBookingNo(),
                    booking.getCurrency(), booking.getAmountMinor(), customerId));
            if ("DEPOSIT".equals(paymentPolicy.getPaymentMode())) {
                long depositMinor = policyService.depositAmount(booking.getAmountMinor(), paymentPolicy);
                if (depositMinor > 0) {
                    booking.setDepositAmountMinor(depositMinor);
                    booking.setDepositOrderId(orderApplicationService.createHotelReceivable(context.tenantId(),
                            context.organizationId(), context.storeId(), booking.getBookingNo() + "-DEP",
                            booking.getCurrency(), depositMinor, customerId));
                }
            }
        } catch (RuntimeException e) {
            if (pointsRedeemed) {
                pointsClient.release(customerId, pointsUsed, booking.getId(), pointsKey);
            }
            throw e;
        }
        booking.setBookedByAccountId(bookedByAccountId);
        booking.setUpdatedAt(now);
        bookingMapper.updateById(booking);

        for (NightQuote quoteNight : quote.nights()) {
            if (availabilityMapper.hold(context.tenantId(), context.storeId(), roomTypeId,
                    quoteNight.stayDate(), rooms) == 0) {
                throw new ApiException(409, "ROOM_AVAILABILITY_UNAVAILABLE", "房量已被其他预订占用，请重新报价");
            }
            HotelRoomAvailabilityHoldPo hold = new HotelRoomAvailabilityHoldPo();
            hold.setTenantId(context.tenantId());
            hold.setOrganizationId(storeScope.organizationId());
            hold.setStoreId(context.storeId());
            hold.setBookingId(booking.getId());
            hold.setRoomTypeId(roomTypeId);
            hold.setStayDate(quoteNight.stayDate());
            hold.setRooms(rooms);
            hold.setStatus("HELD");
            hold.setExpiresAt(booking.getHoldExpiresAt());
            hold.setCreatedAt(now);
            hold.setUpdatedAt(now);
            availabilityHoldMapper.insert(hold);

            HotelBookingNightPo bookingNight = new HotelBookingNightPo();
            bookingNight.setTenantId(context.tenantId());
            bookingNight.setStoreId(context.storeId());
            bookingNight.setBookingId(booking.getId());
            bookingNight.setStayDate(quoteNight.stayDate());
            bookingNight.setPriceMinor(quoteNight.priceMinor());
            bookingNight.setCurrency(booking.getCurrency());
            bookingNight.setStatus("HELD");
            bookingNight.setCreatedAt(now);
            bookingNight.setUpdatedAt(now);
            nightMapper.insert(bookingNight);
        }
        if ("PAY_AT_HOTEL".equals(paymentPolicy.getPaymentMode())) {
            // 到店付：预订直接确认，房量由 confirm 将 HELD 转为 SOLD。
            return confirm(booking.getId());
        }
        // 定金模式保持 PENDING_CONFIRM：前台收定金后调用 confirmDeposit 推进状态。
        return booking;
    }

    /** 定金收款后确认预订；支付动作由支付接口完成，本方法只推进酒店状态机。 */
    @Transactional
    public HotelBookingPo confirmDeposit(Long bookingId) {
        return confirm(bookingId);
    }

    @Transactional
    public HotelBookingPo confirm(Long bookingId) {
        return confirm(bookingId, null);
    }

    /** B 端确认：expectedVersion 非空时做乐观锁校验，防止两个店长重复覆盖。 */
    @Transactional
    public HotelBookingPo confirm(Long bookingId, Integer expectedVersion) {
        PermissionGuard.require("hotel.booking.create");
        TenantContext context = requireStore();
        HotelBookingPo booking = requireBooking(bookingId, context);
        if (expectedVersion != null && !expectedVersion.equals(booking.getVersion())) {
            throw new ApiException(409, "BOOKING_VERSION_CONFLICT", "预订已被其他人更新，请刷新后重试");
        }
        if (!"PENDING_CONFIRM".equals(booking.getStatus())) {
            throw new ApiException(409, "BOOKING_STATE_INVALID", "当前预订不能确认");
        }
        if (booking.getHoldExpiresAt() != null && booking.getHoldExpiresAt().isBefore(LocalDateTime.now())) {
            cancel(bookingId);
            throw new ApiException(409, "HOLD_EXPIRED", "库存占用已过期");
        }
        for (HotelRoomAvailabilityHoldPo hold : holds(bookingId, "HELD")) {
            if (availabilityMapper.confirmHeld(context.tenantId(), context.storeId(), hold.getRoomTypeId(),
                    hold.getStayDate(), hold.getRooms()) == 0) {
                throw new ApiException(409, "ROOM_AVAILABILITY_CONFIRM_CONFLICT", "房量占用状态冲突");
            }
            hold.setStatus("SOLD");
            hold.setUpdatedAt(LocalDateTime.now());
            availabilityHoldMapper.updateById(hold);
        }
        updateNights(bookingId, "SOLD");
        booking.setStatus("CONFIRMED");
        booking.setVersion(booking.getVersion() == null ? 1 : booking.getVersion() + 1);
        booking.setUpdatedAt(LocalDateTime.now());
        bookingMapper.updateById(booking);
        return booking;
    }

    @Transactional
    public HotelBookingPo cancel(Long bookingId) {
        PermissionGuard.require("hotel.booking.create");
        TenantContext context = requireStore();
        HotelBookingPo booking = requireBooking(bookingId, context);
        if ("CANCELLED".equals(booking.getStatus()) || "CHECKED_OUT".equals(booking.getStatus())) {
            throw new ApiException(409, "BOOKING_STATE_INVALID", "当前预订不能取消");
        }
        for (HotelRoomAvailabilityHoldPo hold : holds(bookingId, "HELD")) {
            availabilityMapper.releaseHeld(context.tenantId(), context.storeId(), hold.getRoomTypeId(),
                    hold.getStayDate(), hold.getRooms());
            hold.setStatus("RELEASED");
            hold.setUpdatedAt(LocalDateTime.now());
            availabilityHoldMapper.updateById(hold);
        }
        for (HotelRoomAvailabilityHoldPo hold : holds(bookingId, "SOLD")) {
            availabilityMapper.releaseSold(context.tenantId(), context.storeId(), hold.getRoomTypeId(),
                    hold.getStayDate(), hold.getRooms());
            hold.setStatus("RELEASED");
            hold.setUpdatedAt(LocalDateTime.now());
            availabilityHoldMapper.updateById(hold);
        }
        updateNights(bookingId, "CANCELLED");
        HotelPolicyPo cancelPolicy = policyService.paymentPolicy(booking.getHotelId(), context);
        HotelPolicyApplicationService.CancellationQuote quote =
                policyService.cancellationQuote(booking, cancelPolicy, LocalDateTime.now());
        booking.setCancelFeeMinor(quote.feeMinor());
        booking.setRefundMinor(quote.refundMinor());
        booking.setCancelledAt(LocalDateTime.now());
        booking.setStatus("CANCELLED");
        booking.setUpdatedAt(LocalDateTime.now());
        bookingMapper.updateById(booking);
        if (booking.getPointsUsed() != null && booking.getPointsUsed() > 0 && booking.getBookedByAccountId() != null) {
            Long cancelCustomerId = memberLookupProvider
                    .findMemberId(context.tenantId(), booking.getBookedByAccountId()).orElse(null);
            if (cancelCustomerId != null) {
                pointsClient.release(cancelCustomerId, booking.getPointsUsed(), booking.getId(),
                        "hotel-points:" + booking.getBookingNo());
            }
        }
        return booking;
    }

    /** 单条预订查询：按当前门店上下文校验归属，供渠道适配器等读路径复用。 */
    public HotelBookingPo getBooking(Long bookingId) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        return requireBooking(bookingId, context);
    }

    @Transactional
    public HotelBookingPo noShow(Long bookingId) {
        PermissionGuard.require("hotel.booking.create");
        TenantContext context = requireStore();
        HotelBookingPo booking = requireBooking(bookingId, context);
        if (!"CONFIRMED".equals(booking.getStatus())) {
            throw new ApiException(409, "BOOKING_STATE_INVALID", "当前预订不能标记 no-show");
        }
        for (HotelRoomAvailabilityHoldPo hold : holds(bookingId, "HELD")) {
            availabilityMapper.releaseHeld(context.tenantId(), context.storeId(), hold.getRoomTypeId(),
                    hold.getStayDate(), hold.getRooms());
            hold.setStatus("RELEASED");
            hold.setUpdatedAt(LocalDateTime.now());
            availabilityHoldMapper.updateById(hold);
        }
        for (HotelRoomAvailabilityHoldPo hold : holds(bookingId, "SOLD")) {
            availabilityMapper.releaseSold(context.tenantId(), context.storeId(), hold.getRoomTypeId(),
                    hold.getStayDate(), hold.getRooms());
            hold.setStatus("RELEASED");
            hold.setUpdatedAt(LocalDateTime.now());
            availabilityHoldMapper.updateById(hold);
        }
        updateNights(bookingId, "NO_SHOW");
        booking.setStatus("NO_SHOW");
        booking.setUpdatedAt(LocalDateTime.now());
        bookingMapper.updateById(booking);
        return booking;
    }

    public List<HotelBookingPo> list(String status, LocalDate from, LocalDate to) {
        return list(status, from, to, 100, 0);
    }

    /** B 端酒店预订列表：默认最多 100 条，避免运营页一次拉全量。 */
    public List<HotelBookingPo> list(String status, LocalDate from, LocalDate to, int limit, int offset) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        LambdaQueryWrapper<HotelBookingPo> query = new LambdaQueryWrapper<HotelBookingPo>()
                .eq(HotelBookingPo::getStoreId, context.storeId())
                .orderByDesc(HotelBookingPo::getId)
                .last("LIMIT " + safeLimit + " OFFSET " + safeOffset);
        if (status != null && !status.isBlank()) query.eq(HotelBookingPo::getStatus, status);
        if (from != null) query.ge(HotelBookingPo::getCheckInDate, from);
        if (to != null) query.lt(HotelBookingPo::getCheckOutDate, to);
        List<HotelBookingPo> rows = bookingMapper.selectList(query);
        maskGuestPii(rows, context);
        return rows;
    }

    public List<HotelBookingPo> listByAccount(String status, LocalDate from, LocalDate to, Long accountId) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        if (accountId == null || accountId <= 0) {
            throw new ApiException(401, "ACCOUNT_REQUIRED", "缺少账号");
        }
        // C 端消费应用绑定一个安装门店（如 KTV），同一租户的酒店订单可能属于
        // 另一个酒店门店；本人订单按账号 + 租户聚合，不能再用当前安装门店收窄。
        LambdaQueryWrapper<HotelBookingPo> query = new LambdaQueryWrapper<HotelBookingPo>()
                .eq(HotelBookingPo::getBookedByAccountId, accountId)
                .orderByDesc(HotelBookingPo::getId)
                .last("LIMIT 100");
        if (status != null && !status.isBlank()) query.eq(HotelBookingPo::getStatus, status);
        if (from != null) query.ge(HotelBookingPo::getCheckInDate, from);
        if (to != null) query.lt(HotelBookingPo::getCheckOutDate, to);
        List<HotelBookingPo> rows = bookingMapper.selectList(query);
        maskGuestPii(rows, context);
        return rows;
    }


    /** B 端无 member.pii.view 时掩码住客姓名/手机号；C 本人订单保持完整。 */
    private static void maskGuestPii(List<HotelBookingPo> rows, TenantContext context) {
        boolean canViewPii = context.permissions() != null
                && context.permissions().contains("member.pii.view");
        if (canViewPii || rows == null) {
            return;
        }
        for (HotelBookingPo row : rows) {
            if (row.getBookedByAccountId() != null
                    && row.getBookedByAccountId() == context.accountId()) {
                continue;
            }
            row.setGuestName(maskName(row.getGuestName()));
            row.setGuestPhone(maskPhone(row.getGuestPhone()));
        }
    }

    private static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return name.charAt(0) + "**";
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
    private List<HotelRoomAvailabilityHoldPo> holds(Long bookingId, String status) {
        return availabilityHoldMapper.selectList(new LambdaQueryWrapper<HotelRoomAvailabilityHoldPo>()
                .eq(HotelRoomAvailabilityHoldPo::getBookingId, bookingId)
                .eq(HotelRoomAvailabilityHoldPo::getStatus, status));
    }

    /** 每日房价优先，未配置时回退房价计划基础价。 */
    private long dailyPrice(TenantContext context, Long roomTypeId, HotelRatePlanPo ratePlan, LocalDate date) {
        HotelDailyRatePo daily = dailyRateMapper.selectOne(new LambdaQueryWrapper<HotelDailyRatePo>()
                .eq(HotelDailyRatePo::getStoreId, context.storeId())
                .eq(HotelDailyRatePo::getRoomTypeId, roomTypeId)
                .eq(HotelDailyRatePo::getRatePlanId, ratePlan.getId())
                .eq(HotelDailyRatePo::getStayDate, date));
        return daily == null ? ratePlan.getBasePriceMinor() : daily.getPriceMinor();
    }

    private void updateNights(Long bookingId, String status) {
        for (HotelBookingNightPo night : nightMapper.selectList(new LambdaQueryWrapper<HotelBookingNightPo>()
                .eq(HotelBookingNightPo::getBookingId, bookingId))) {
            night.setStatus(status);
            night.setUpdatedAt(LocalDateTime.now());
            nightMapper.updateById(night);
        }
    }

    /** 未支付 hold 到期释放；调度跨租户，逐条按 hold 自身 tenant/store 释放。 */
    @Scheduled(fixedDelay = 60_000L)
    @Transactional
    public void expireHolds() {
        for (HotelRoomAvailabilityHoldPo hold : availabilityHoldMapper.selectExpired()) {
            if (availabilityHoldMapper.releaseExpired(hold.getId()) > 0) {
                availabilityMapper.releaseHeld(hold.getTenantId(), hold.getStoreId(), hold.getRoomTypeId(),
                        hold.getStayDate(), hold.getRooms());
                nightMapper.cancelHeldNights(hold.getBookingId());
                bookingMapper.cancelPending(hold.getBookingId());
            }
        }
    }

    private HotelRoomAvailabilityPo availability(TenantContext context, Long roomTypeId, LocalDate date) {
        return availabilityMapper.selectOne(new LambdaQueryWrapper<HotelRoomAvailabilityPo>()
                .eq(HotelRoomAvailabilityPo::getStoreId, context.storeId())
                .eq(HotelRoomAvailabilityPo::getRoomTypeId, roomTypeId)
                .eq(HotelRoomAvailabilityPo::getStayDate, date));
    }

    private HotelBookingPo requireBooking(Long id, TenantContext context) {
        HotelBookingPo booking = bookingMapper.selectById(id);
        if (booking == null || !context.storeId().equals(booking.getStoreId())) {
            throw new ApiException(404, "HOTEL_BOOKING_NOT_FOUND", "酒店预订不存在");
        }
        return booking;
    }

    private HotelRoomTypePo requireRoomType(Long id, TenantContext context) {
        HotelRoomTypePo roomType = roomTypeMapper.selectById(id);
        if (roomType == null || !context.storeId().equals(roomType.getStoreId())) {
            throw new ApiException(404, "ROOM_TYPE_NOT_FOUND", "房型不存在");
        }
        return roomType;
    }

    private HotelRatePlanPo requireRatePlan(Long id, Long hotelId, TenantContext context) {
        HotelRatePlanPo ratePlan = id == null ? null : ratePlanMapper.selectById(id);
        if (ratePlan == null) {
            ratePlan = ratePlanMapper.selectOne(new LambdaQueryWrapper<HotelRatePlanPo>()
                    .eq(HotelRatePlanPo::getStoreId, context.storeId())
                    .eq(HotelRatePlanPo::getHotelId, hotelId)
                    .eq(HotelRatePlanPo::getStatus, "ACTIVE")
                    .orderByAsc(HotelRatePlanPo::getId)
                    .last("LIMIT 1"));
        }
        if (ratePlan == null || !context.storeId().equals(ratePlan.getStoreId())
                || !hotelId.equals(ratePlan.getHotelId())) {
            throw new ApiException(404, "RATE_PLAN_NOT_FOUND", "房价计划不存在");
        }
        return ratePlan;
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "WEB";
        }
        String trimmed = source.trim();
        return trimmed.length() <= 24 ? trimmed : trimmed.substring(0, 24);
    }

    /**
     * 在房型所属酒店门店上下文中执行 C 端酒店用例。
     *
     * <p>C 消费应用按门店安装（如 KTV 门店），同租户的酒店房型仍应可预订；
     * 这里只用房型的权威 storeId 扩展上下文，权限快照仍来自当前消费者会话。
     * 非当前租户/门店不匹配的房型按不存在处理。
     */
    private <T> T inRoomTypeStore(Long roomTypeId, Supplier<T> action) {
        TenantContext base = requireStore();
        HotelRoomTypePo roomType = roomTypeId == null ? null : roomTypeMapper.selectById(roomTypeId);
        if (roomType == null || roomType.getStoreId() == null
                || roomType.getTenantId() == null || base.tenantId() != roomType.getTenantId()) {
            throw new ApiException(404, "ROOM_TYPE_NOT_FOUND", "房型不存在");
        }
        TenantContext previous = TenantContextHolder.get();
        TenantContextHolder.set(new TenantContext(base.tenantId(), base.organizationId(),
                roomType.getStoreId(), base.accountId(), base.authorizationVersion(), base.permissions(),
                base.scopeType(), "HOTEL", base.timezone()));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.set(previous);
            }
        }
    }

    private void validateStay(LocalDate checkIn, LocalDate checkOut, int rooms) {
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn) || rooms < 1) {
            throw new ApiException(400, "STAY_INVALID", "入住日期、离店日期或房间数不合法");
        }
    }

    private TenantContext requireStore() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null) {
            throw new ApiException(403, "STORE_SCOPE_REQUIRED", "酒店预订必须在门店上下文操作");
        }
        return context;
    }

    public record NightQuote(LocalDate stayDate, long priceMinor, int available) {}

    public record Quote(boolean available, long totalMinor, String currency, List<NightQuote> nights,
                        LocalDateTime holdExpiresAt) {}
}
