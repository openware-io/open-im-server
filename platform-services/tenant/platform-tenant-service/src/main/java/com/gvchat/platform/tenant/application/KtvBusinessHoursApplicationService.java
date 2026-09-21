package com.gvchat.platform.tenant.application;

import com.gvchat.platform.tenant.infra.persistence.mapper.InternalTenantConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * KTV 营业时间配置的唯一读取口径（门店级覆盖租户默认）。
 *
 * <p><b>为什么需要它</b>：KTV 是夜间业态，默认营业时间是 <b>18:00 – 次日 05:00</b>（跨自然日）。
 * 「预约到店时间必须落在营业时段内」这一条规则要在所有入口一致生效（C 端下单、B 端/后台代客预约、
 * 服务端创建接口），因此营业时间必须是一个**可配置的单一事实来源**，而不是各处写死。
 *
 * <p><b>存储</b>：{@code tnt_tenant_config} 的键 {@code ktv_business_hours}，值形如 {@code 18:00-05:00}；
 * {@code store_id = 0} 表示租户默认，门店行（{@code store_id = 门店ID}）覆盖租户默认。
 * 生效顺序：门店行 → 租户默认行 → 代码缺省 {@code 18:00-05:00}。
 *
 * <p><b>语义</b>：区间为 <b>左闭右开 {@code [open, close)}</b>；{@code open > close} 表示跨自然日
 * （例如 18:00–05:00 覆盖当天 18:00 到次日 05:00）；{@code open == close} 视为<b>全天营业</b>。
 *
 * <p><b>读路径绝不抛异常</b>：缺行、空值、格式非法一律回退缺省值（格式非法记 WARN），
 * 否则一份写坏的配置会让所有预约入口报错。
 */
@Slf4j
@Service
public class KtvBusinessHoursApplicationService {

    /** 配置键：KTV 营业时间（值形如 {@code 18:00-05:00}）。 */
    public static final String CONFIG_KEY = "ktv_business_hours";
    /** 缺省开始营业时间（KTV 夜间业态）。 */
    public static final LocalTime DEFAULT_OPEN = LocalTime.of(18, 0);
    /** 缺省结束营业时间（次日）。 */
    public static final LocalTime DEFAULT_CLOSE = LocalTime.of(5, 0);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SEPARATOR = "-";

    private final InternalTenantConfigMapper tenantConfigMapper;

    public KtvBusinessHoursApplicationService(InternalTenantConfigMapper tenantConfigMapper) {
        this.tenantConfigMapper = tenantConfigMapper;
    }

    /**
     * 生效营业时间：门店行 → 租户默认行 → 代码缺省。
     *
     * @param tenantId 目标租户（null/非正 → 缺省值）
     * @param storeId  目标门店（null/0 → 只用租户默认，不读门店行）
     */
    public KtvBusinessHours resolve(Long tenantId, Long storeId) {
        if (tenantId == null || tenantId <= 0) {
            return KtvBusinessHours.DEFAULT;
        }
        if (storeId != null && storeId > 0) {
            KtvBusinessHours store = parse(
                    tenantConfigMapper.selectStoreConfigValue(tenantId, storeId, CONFIG_KEY), "STORE");
            if (store != null) {
                return store;
            }
        }
        KtvBusinessHours tenant = parse(
                tenantConfigMapper.selectTenantConfigValue(tenantId, CONFIG_KEY), "TENANT");
        return tenant == null ? KtvBusinessHours.DEFAULT : tenant;
    }

    /** 解析配置值；缺行/空值返回 null（调用方继续回退），格式非法记 WARN 后按缺行处理。 */
    private KtvBusinessHours parse(String raw, String source) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        int separator = value.indexOf(SEPARATOR);
        if (separator <= 0 || separator == value.length() - 1) {
            log.warn("非法营业时间配置（应为 HH:mm-HH:mm），按缺省处理: source={}, value={}", source, value);
            return null;
        }
        try {
            LocalTime open = LocalTime.parse(value.substring(0, separator).trim(), TIME_FORMAT);
            LocalTime close = LocalTime.parse(value.substring(separator + 1).trim(), TIME_FORMAT);
            return new KtvBusinessHours(open, close, source);
        } catch (DateTimeParseException invalid) {
            log.warn("非法营业时间配置（时间格式错误），按缺省处理: source={}, value={}", source, value);
            return null;
        }
    }

    /** 归一化为配置值字符串（{@code HH:mm-HH:mm}），写路径与读路径共用同一格式。 */
    public static String format(LocalTime open, LocalTime close) {
        return open.format(TIME_FORMAT) + SEPARATOR + close.format(TIME_FORMAT);
    }

    /**
     * 对外响应视图：时间统一为 {@code HH:mm} 字符串。
     *
     * <p>为什么不用 {@code LocalTime} 直接序列化：跨服务（tenant → order）与跨端（后台 / C 端）
     * 对 Java 时间对象的序列化口径不一致（数组/带秒/带时区），字符串是唯一不会漂移的形态。
     */
    public record BusinessHoursView(Long storeId, String openTime, String closeTime, String source,
                                    boolean crossesMidnight, boolean allDay, String displayText) {

        public static BusinessHoursView of(Long storeId, KtvBusinessHours hours) {
            return new BusinessHoursView(storeId,
                    hours.open().format(TIME_FORMAT), hours.close().format(TIME_FORMAT),
                    hours.source(), hours.crossesMidnight(), hours.allDay(), hours.displayText());
        }
    }

    /**
     * 生效营业时间。
     *
     * @param open         开始营业时间（左闭）
     * @param close        结束营业时间（右开；早于 open 表示跨自然日）
     * @param source       STORE / TENANT / DEFAULT，说明该值来自哪一层（界面可显示「继承租户默认」）
     */
    public record KtvBusinessHours(LocalTime open, LocalTime close, String source) {

        /** 缺省配置（与服务端其余缺省口径一致）。 */
        public static final KtvBusinessHours DEFAULT =
                new KtvBusinessHours(DEFAULT_OPEN, DEFAULT_CLOSE, "DEFAULT");

        /** 是否跨自然日（例如 18:00–05:00）。 */
        public boolean crossesMidnight() {
            return close.isBefore(open);
        }

        /** 是否全天营业（open == close）。 */
        public boolean allDay() {
            return close.equals(open);
        }

        /**
         * 某个时刻是否落在营业时段内（左闭右开；跨自然日时 {@code t >= open || t < close}）。
         */
        public boolean contains(LocalTime time) {
            if (time == null) {
                return false;
            }
            if (allDay()) {
                return true;
            }
            if (crossesMidnight()) {
                return !time.isBefore(open) || time.isBefore(close);
            }
            return !time.isBefore(open) && time.isBefore(close);
        }

        /** 展示文案：跨自然日时标注「次日」。 */
        public String displayText() {
            if (allDay()) {
                return "全天营业";
            }
            return open.format(TIME_FORMAT) + (crossesMidnight() ? " – 次日 " : " – ") + close.format(TIME_FORMAT);
        }

        /** 配置值字符串（{@code HH:mm-HH:mm}）。 */
        public String configValue() {
            return format(open, close);
        }
    }
}
