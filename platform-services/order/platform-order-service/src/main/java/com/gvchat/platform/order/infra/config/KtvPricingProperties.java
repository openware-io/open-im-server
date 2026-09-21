package com.gvchat.platform.order.infra.config;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * KTV 计价方案可配置默认值（application.yml 前缀 {@code ktv.pricing}）。
 * 首发占位默认（KTV_BUSINESS_01 §5.3）：包厢 100 元/时、服务人员 50 元/时、标准时长 120 分钟。
 * 金额为最小货币单位整数。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ktv.pricing")
public class KtvPricingProperties {

    /** 包厢计费单位 HOUR/HALF_HOUR/PACKAGE。 */
    private String billingUnit = "HOUR";

    /** 包厢每计费单位单价（最小货币单位，默认 10000 = ¥100）。 */
    private long roomUnitPrice = 10000L;

    /** 标准时长分钟（无预订判定超时，默认 120）。 */
    private int defaultSessionMinutes = 120;

    /** 免费等待分钟（默认 0）。 */
    private int freeWaitMinutes = 0;

    /** 超时费率（默认 1.0）。 */
    private BigDecimal overtimeRate = BigDecimal.ONE;

    /** 服务人员递增粒度分钟（默认 30，半小时递增）。 */
    private int incrementMinutes = 30;

    /** 服务人员舍入方向 CONSUMER_FAVOR/ROUND_UP/FLOOR_BLOCK（默认让利）。 */
    private String roundingDirection = "CONSUMER_FAVOR";

    /** 服务人员小时单价（最小货币单位，默认 5000 = ¥50/时）。 */
    private long serverPricePerHour = 5000L;
}
