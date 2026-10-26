package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 酒店店铺策略：支付/押金/取消/积分开关；默认值只在未落库时返回。 */
@Service
public class HotelPolicyApplicationService {

    private static final Set<String> PAYMENT_MODES = Set.of("PREPAY", "PAY_AT_HOTEL", "DEPOSIT");
    private static final Set<String> DEPOSIT_MODES = Set.of("COLLECT", "PRE_AUTH");
    private static final Set<String> ACCESS_MODES = Set.of("SINGLE_STORE", "GROUP");

    private final HotelPolicyMapper policyMapper;
    private final HotelMapper hotelMapper;

    public HotelPolicyApplicationService(HotelPolicyMapper policyMapper, HotelMapper hotelMapper) {
        this.policyMapper = policyMapper;
        this.hotelMapper = hotelMapper;
    }

    public HotelPolicyPo current(Long hotelId) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        HotelPo hotel = requireHotel(hotelId, context);
        HotelPolicyPo policy = find(hotel, context);
        return policy == null ? defaults(hotel, context) : policy;
    }

    @Transactional
    public HotelPolicyPo save(Long hotelId, String accessMode, String entryPoints, String paymentMode,
                              Integer depositRatioBps, Boolean stayDepositRequired, String stayDepositMode,
                              Long stayDepositMinor, Integer stayDepositRatioBps, String cancelPolicy,
                              Boolean pointsRedeemEnabled, Integer pointsMaxRatioBps) {
        PermissionGuard.require("hotel.inventory.manage");
        TenantContext context = requireStore();
        HotelPo hotel = requireHotel(hotelId, context);
        validate(accessMode, paymentMode, stayDepositMode, depositRatioBps, stayDepositMinor,
                stayDepositRatioBps, pointsMaxRatioBps);
        HotelPolicyPo po = find(hotel, context);
        LocalDateTime now = LocalDateTime.now();
        if (po == null) {
            po = new HotelPolicyPo();
            po.setTenantId(context.tenantId());
            po.setStoreId(context.storeId());
            po.setHotelId(hotel.getId());
            po.setVersion(0);
            po.setCreatedBy(context.accountId());
            po.setUpdatedBy(context.accountId());
            po.setCreatedAt(now);
        }
        po.setAccessMode(blank(accessMode) ? "SINGLE_STORE" : accessMode);
        po.setEntryPoints(entryPoints);
        po.setPaymentMode(blank(paymentMode) ? "PREPAY" : paymentMode);
        po.setDepositRatioBps(depositRatioBps == null ? 3000 : depositRatioBps);
        po.setStayDepositRequired(stayDepositRequired == null || stayDepositRequired);
        po.setStayDepositMode(blank(stayDepositMode) ? "COLLECT" : stayDepositMode);
        po.setStayDepositMinor(stayDepositMinor == null ? 0 : stayDepositMinor);
        po.setStayDepositRatioBps(stayDepositRatioBps == null ? 0 : stayDepositRatioBps);
        po.setCancelPolicy(blank(cancelPolicy) ? "FREE_UNTIL_24H" : cancelPolicy);
        po.setPointsRedeemEnabled(pointsRedeemEnabled != null && pointsRedeemEnabled);
        po.setPointsMaxRatioBps(pointsMaxRatioBps == null ? 0 : pointsMaxRatioBps);
        po.setInvoiceMode("NONE");
        po.setUpdatedBy(context.accountId());
        po.setUpdatedAt(now);
        if (po.getId() == null) {
            policyMapper.insert(po);
        } else {
            policyMapper.updateById(po);
        }
        return po;
    }

    /** 定金金额：固定金额优先，否则按订单金额比例，最多不超过订单总额。 */
    public long depositAmount(long totalMinor, HotelPolicyPo policy) {
        if (totalMinor <= 0) {
            return 0;
        }
        long fixed = policy.getStayDepositMinor() == null ? 0 : policy.getStayDepositMinor();
        if (fixed > 0) {
            return Math.min(fixed, totalMinor);
        }
        int bps = policy.getDepositRatioBps() == null ? 3000 : policy.getDepositRatioBps();
        return Math.max(1, Math.min(totalMinor, Math.round(totalMinor * bps / 10_000.0)));
    }

    /** 下单读取支付策略；没有配置时按预付处理，保持既有订单状态机。 */
    public HotelPolicyPo paymentPolicy(Long hotelId, TenantContext context) {
        HotelPolicyPo policy = find(hotelId, context);
        if (policy != null) {
            return policy;
        }
        HotelPo hotel = new HotelPo();
        hotel.setId(hotelId);
        return defaults(hotel, context);
    }

    private HotelPolicyPo find(HotelPo hotel, TenantContext context) {
        return find(hotel.getId(), context);
    }

    private HotelPolicyPo find(Long hotelId, TenantContext context) {
        return policyMapper.selectOne(new LambdaQueryWrapper<HotelPolicyPo>()
                .eq(HotelPolicyPo::getTenantId, context.tenantId())
                .eq(HotelPolicyPo::getStoreId, context.storeId())
                .eq(HotelPolicyPo::getHotelId, hotelId));
    }

    private HotelPolicyPo defaults(HotelPo hotel, TenantContext context) {
        HotelPolicyPo po = new HotelPolicyPo();
        po.setTenantId(context.tenantId());
        po.setStoreId(context.storeId());
        po.setHotelId(hotel.getId());
        po.setAccessMode("SINGLE_STORE");
        po.setPaymentMode("PREPAY");
        po.setDepositRatioBps(3000);
        po.setStayDepositRequired(true);
        po.setStayDepositMode("COLLECT");
        po.setStayDepositMinor(0L);
        po.setStayDepositRatioBps(0);
        po.setCancelPolicy("FREE_UNTIL_24H");
        po.setPointsRedeemEnabled(false);
        po.setPointsMaxRatioBps(0);
        po.setInvoiceMode("NONE");
        po.setVersion(0);
        return po;
    }

    private void validate(String accessMode, String paymentMode, String stayDepositMode, Integer depositRatioBps,
                          Long stayDepositMinor, Integer stayDepositRatioBps, Integer pointsMaxRatioBps) {
        if (!blank(accessMode) && !ACCESS_MODES.contains(accessMode)) {
            throw new ApiException(400, "HOTEL_ACCESS_MODE_INVALID", "酒店接入模式不合法");
        }
        if (!blank(paymentMode) && !PAYMENT_MODES.contains(paymentMode)) {
            throw new ApiException(400, "HOTEL_PAYMENT_MODE_INVALID", "支付模式不合法");
        }
        if (!blank(stayDepositMode) && !DEPOSIT_MODES.contains(stayDepositMode)) {
            throw new ApiException(400, "HOTEL_DEPOSIT_MODE_INVALID", "押金模式不合法");
        }
        requireBps(depositRatioBps, "HOTEL_DEPOSIT_RATIO_INVALID");
        requireBps(stayDepositRatioBps, "HOTEL_STAY_DEPOSIT_RATIO_INVALID");
        requireBps(pointsMaxRatioBps, "HOTEL_POINTS_RATIO_INVALID");
        if (stayDepositMinor != null && stayDepositMinor < 0) {
            throw new ApiException(400, "HOTEL_DEPOSIT_AMOUNT_INVALID", "押金金额不能为负数");
        }
    }

    private void requireBps(Integer value, String code) {
        if (value != null && (value < 0 || value > 10_000)) {
            throw new ApiException(400, code, "比例必须在 0 到 10000 之间");
        }
    }

    private HotelPo requireHotel(Long hotelId, TenantContext context) {
        if (hotelId == null) {
            throw new ApiException(400, "HOTEL_REQUIRED", "酒店不能为空");
        }
        HotelPo hotel = hotelMapper.selectOne(new LambdaQueryWrapper<HotelPo>()
                .eq(HotelPo::getId, hotelId)
                .eq(HotelPo::getStoreId, context.storeId()));
        if (hotel == null) {
            throw new ApiException(404, "HOTEL_NOT_FOUND", "酒店不存在");
        }
        return hotel;
    }

    private TenantContext requireStore() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null || context.storeId() <= 0) {
            throw new ApiException(403, "STORE_CONTEXT_REQUIRED", "需要门店上下文");
        }
        return context;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
