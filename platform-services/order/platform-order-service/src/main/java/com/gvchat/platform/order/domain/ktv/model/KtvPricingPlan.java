package com.gvchat.platform.order.domain.ktv.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * KTV 计价方案（价值对象，开台/结台时由 {@code KtvPricingPlanProvider} 解析并固化快照）。
 * 金额一律为最小货币单位整数（如 CNY 分），避免浮点小数。
 *
 * <p>按房型定价：门店可维护房型字典（res_room_type，每房型一个单价），资源带 room_type_id 时房费/服务费
 * 取该房型的单价；房型未设置或该房型没有单价时回退门店级单价（{@code roomUnitPrice}/{@code serverPricePerInc}，
 * 来自租户后台计价方案或服务默认值）。已经生效的价格直接落在本对象的 {@code roomUnitPrice}/{@code serverPricePerInc}
 * 上，房型信息另存 {@code appliedRoomTypeCode}/{@code appliedRoomTypeName}/{@code roomTypePriceApplied} 并写进快照，
 * 保证「快照固化实际使用的房型与单价」。
 *
 * <p>{@code unitPriceByRoomType} 是租户后台计价方案按房型下发的单价（房型编码 -&gt; 每计费单位单价），
 * 作为房型单价的第二取值来源（资源侧未定价但方案按房型定价时生效）；缺该房型一律回退门店级单价。
 *
 * <p>{@code serverUnitPrice} 是「服务单价」的**每计费单位**口径（与 {@code roomUnitPrice} 同单位），
 * 与 {@code serverPricePerInc}（每递增粒度，服务人员会话费实际使用）互为换算：
 * 「房型单价 + 服务单价 = 合计单价」既是对客展示口径，也是新口径下的**包厢费计费基数**。
 *
 * <p>{@code roomFeeIncludesServer} 标记结台房费基数（默认 {@code true}，由计价方案 provider 置位）：
 * 为真时 {@link #billableRoomPricePerIncrement()} = 房费递增价 + 服务递增价，即包厢费**已含 1 名标准服务人员**，
 * 第 2 名起由服务人员会话另行计费；为假（历史快照/旧口径）时只按 {@link #roomPricePerIncrement()} 计，
 * 存量会话不被追溯涨价。
 */
public record KtvPricingPlan(
        KtvBillingUnit billingUnit,
        long roomUnitPrice,
        int defaultSessionMinutes,
        int freeWaitMinutes,
        BigDecimal overtimeRate,
        int incrementMinutes,
        KtvRoundingDirection roundingDirection,
        long serverPricePerInc,
        Map<String, Long> unitPriceByRoomType,
        String appliedRoomTypeCode,
        String appliedRoomTypeName,
        boolean roomTypePriceApplied,
        long serverUnitPrice,
        boolean roomFeeIncludesServer) {

    /** 包厢费（含 1 名标准服务人员）时快照固化的免费服务人员数。 */
    public static final int INCLUDED_SERVER_COUNT = 1;

    /** 兼容构造：不含按房型定价信息（门店级单价方案）；旧口径（包厢费不含服务人员）。 */
    public KtvPricingPlan(KtvBillingUnit billingUnit, long roomUnitPrice, int defaultSessionMinutes,
                          int freeWaitMinutes, BigDecimal overtimeRate, int incrementMinutes,
                          KtvRoundingDirection roundingDirection, long serverPricePerInc) {
        this(billingUnit, roomUnitPrice, defaultSessionMinutes, freeWaitMinutes, overtimeRate, incrementMinutes,
                roundingDirection, serverPricePerInc, Map.of(), null, null, false,
                serverUnitPriceOf(billingUnit, incrementMinutes, serverPricePerInc), false);
    }

    /**
     * 兼容构造：不含「每计费单位服务单价」与计费基数标记（历史快照 / 增量调用），按**旧口径**还原。
     * 服务单价由已固化的每递增粒度服务价反推，保证 {@code combinedUnitPrice} 与计费口径自洽。
     */
    public KtvPricingPlan(KtvBillingUnit billingUnit, long roomUnitPrice, int defaultSessionMinutes,
                          int freeWaitMinutes, BigDecimal overtimeRate, int incrementMinutes,
                          KtvRoundingDirection roundingDirection, long serverPricePerInc,
                          Map<String, Long> unitPriceByRoomType, String appliedRoomTypeCode,
                          String appliedRoomTypeName, boolean roomTypePriceApplied) {
        this(billingUnit, roomUnitPrice, defaultSessionMinutes, freeWaitMinutes, overtimeRate, incrementMinutes,
                roundingDirection, serverPricePerInc, unitPriceByRoomType, appliedRoomTypeCode,
                appliedRoomTypeName, roomTypePriceApplied,
                serverUnitPriceOf(billingUnit, incrementMinutes, serverPricePerInc), false);
    }

    /**
     * 覆盖「每计费单位服务单价」+「包厢费是否已含 1 名标准服务人员」：
     * 从快照还原时保持分项与计费基数原值，保证结台/预估/账单与开台时固化的一致。
     */
    public KtvPricingPlan withSnapshotPricing(long serverUnitPrice, boolean roomFeeIncludesServer) {
        return new KtvPricingPlan(billingUnit, roomUnitPrice, defaultSessionMinutes, freeWaitMinutes, overtimeRate,
                incrementMinutes, roundingDirection, serverPricePerInc, unitPriceByRoomType, appliedRoomTypeCode,
                appliedRoomTypeName, roomTypePriceApplied, serverUnitPrice, roomFeeIncludesServer);
    }

    /** 置为「包厢费已含 1 名标准服务人员」的新口径（开台生成的新方案用；历史快照保持 false）。 */
    public KtvPricingPlan withRoomFeeIncludesServer(boolean roomFeeIncludesServer) {
        return withSnapshotPricing(serverUnitPrice, roomFeeIncludesServer);
    }

    /**
     * 每计费单位服务单价 ← 每递增粒度服务价（{@link #serverUnitPricePerIncrement} 的逆换算）。
     * 计费单位为套餐（不按时长递增）时无法换算，直接同值，避免除零。
     */
    private static long serverUnitPriceOf(KtvBillingUnit billingUnit, int incrementMinutes, long serverPricePerInc) {
        int unitMinutes = unitMinutesOf(billingUnit);
        if (unitMinutes <= 0) {
            return serverPricePerInc;
        }
        int increment = incrementMinutes > 0 ? incrementMinutes : 30;
        return serverPricePerInc * unitMinutes / increment;
    }

    /**
     * 按包厢实际使用的房型生成生效方案：房费/服务费优先取房型单价，缺该房型（或无房型）回退门店级单价。
     *
     * @param roomTypeCode          资源上的房型编码（null/空 = 资源未设置房型，整单走门店级单价）
     * @param roomTypeName          房型名称（写入快照，供页面与账单展示）
     * @param roomTypeUnitPrice     房型房费单价（最小货币单位/计费单位；null 或 &lt;= 0 = 该房型未定价）
     * @param roomTypeServerUnitPrice 房型服务人员单价（最小货币单位/计费单位；null 或 &lt;= 0 = 该房型未定价）
     */
    public KtvPricingPlan forRoomType(String roomTypeCode, String roomTypeName,
                                      Long roomTypeUnitPrice, Long roomTypeServerUnitPrice) {
        if (roomTypeCode == null || roomTypeCode.isBlank()) {
            return this;
        }
        Long fromPlan = priceByRoomType(roomTypeCode);
        boolean priced = positive(roomTypeUnitPrice) || positive(fromPlan);
        long effectiveRoomPrice = positive(roomTypeUnitPrice) ? roomTypeUnitPrice
                : positive(fromPlan) ? fromPlan : roomUnitPrice;
        // 服务单价：房型字典有值取房型值（每计费单位，与房型房费同一口径），否则回退门店级/方案值。
        boolean serverPriced = positive(roomTypeServerUnitPrice);
        long effectiveServerUnitPrice = serverPriced ? roomTypeServerUnitPrice : serverUnitPrice;
        long effectiveServerPriceInc = serverPriced
                ? serverUnitPricePerIncrement(roomTypeServerUnitPrice)
                : serverPricePerInc;
        return new KtvPricingPlan(billingUnit, effectiveRoomPrice, defaultSessionMinutes, freeWaitMinutes,
                overtimeRate, incrementMinutes, roundingDirection, effectiveServerPriceInc,
                unitPriceByRoomType, roomTypeCode, roomTypeName, priced, effectiveServerUnitPrice,
                roomFeeIncludesServer);
    }

    /** 计价方案中该房型的单价（房型编码 -&gt; 每计费单位单价）；未配置返回 null。 */
    public Long priceByRoomType(String roomTypeCode) {
        return roomTypeCode == null || unitPriceByRoomType == null ? null : unitPriceByRoomType.get(roomTypeCode);
    }

    /**
     * 房型服务人员单价（每计费单位）换算为「每递增粒度单价」：与 {@link #roomPricePerIncrement()} 同一口径
     * （单价 × 递增粒度分钟 / 计费单位分钟），整数截断，零头让给消费者。
     */
    private long serverUnitPricePerIncrement(long serverUnitPrice) {
        int unitMinutes = billingUnitMinutes();
        if (unitMinutes <= 0) {
            // 套餐（PACKAGE）不按时长递增计费，服务人员单价保持门店级口径，避免除零。
            return serverPricePerInc;
        }
        return serverUnitPrice * effectiveIncrementMinutes() / unitMinutes;
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    /** 计费单位分钟数：HOUR=60、HALF_HOUR=30、PACKAGE=0（套餐按时长计费不适用）。 */
    public int billingUnitMinutes() {
        return unitMinutesOf(billingUnit);
    }

    /** 计费单位分钟数的静态口径（构造期反推服务单价时也要用，避免两套换算）。 */
    private static int unitMinutesOf(KtvBillingUnit billingUnit) {
        int seconds = billingUnit == null ? 0 : billingUnit.seconds();
        return seconds <= 0 ? 0 : seconds / 60;
    }

    /**
     * 每计费单位单价合计 = 房型单价 + 服务单价（对客展示口径，也是
     * {@code roomFeeIncludesServer=true} 时的**包厢费计费基数**）。
     */
    public long combinedUnitPrice() {
        return roomUnitPrice + serverUnitPrice;
    }

    /** 每递增粒度单价合计 = 房费递增价 + 服务人员递增价（与 {@link #combinedUnitPrice()} 同口径）。 */
    public long combinedPricePerIncrement() {
        return roomPricePerIncrement() + serverPricePerInc;
    }

    /**
     * 结台/预估使用的**包厢费每递增粒度基数**：
     * 新口径（{@code roomFeeIncludesServer=true}，包厢费已含 1 名标准服务人员）取
     * {@link #combinedPricePerIncrement()}；旧口径（历史快照）仍取 {@link #roomPricePerIncrement()}，
     * 存量会话不被追溯涨价。
     *
     * <p>唯一区别是基数，递增粒度/取整/超时倍率等规则原样复用（见 {@code KtvRoomFeeCalculator}）。
     */
    public long billableRoomPricePerIncrement() {
        return roomFeeIncludesServer ? combinedPricePerIncrement() : roomPricePerIncrement();
    }

    /** 递增粒度分钟（未配置或非正数时回退 30，与 {@link KtvRoundingDirection#minutesToBlocks} 同口径）。 */
    public int effectiveIncrementMinutes() {
        return incrementMinutes > 0 ? incrementMinutes : 30;
    }

    /** 是否按时长计费（PACKAGE 为一口价套餐，不走时长递增计费）。 */
    public boolean durationBillable() {
        return billingUnitMinutes() > 0;
    }

    /**
     * 每递增粒度单价（最小货币单位整数）= 计费单位价 × 递增粒度分钟 / 计费单位分钟。
     * 例：¥100/小时（10000）× 30 分钟递增 → 每 30 分钟 5000 分；整数截断，零头让给消费者。
     */
    public long roomPricePerIncrement() {
        int unitMinutes = billingUnitMinutes();
        if (unitMinutes <= 0) {
            return 0L;
        }
        return roomUnitPrice * effectiveIncrementMinutes() / unitMinutes;
    }

    /** 舍入方向（未配置时回退默认让利消费者）。 */
    public KtvRoundingDirection effectiveRoundingDirection() {
        return roundingDirection == null ? KtvRoundingDirection.CONSUMER_FAVOR : roundingDirection;
    }

    /** 超时费率（未配置时回退 1.0，避免结台空指针）。 */
    public BigDecimal effectiveOvertimeRate() {
        return overtimeRate == null ? BigDecimal.ONE : overtimeRate;
    }

    /**
     * 计价规则快照（写入 billing_rule_snapshot_json / price_snapshot_json）。
     * 必须固化「实际参与计费」的全部字段：计费单位、单位价（已含房型价）、递增粒度、舍入方向、超时费率、标准时长、
     * 每递增粒度单价，外加本次实际使用的房型与「是否命中房型价」——结台/实时预估/账单三处口径一致的依据。
     *
     * <p>同时固化分项与合计（{@code serverUnitPrice} = 每计费单位服务单价、
     * {@code combinedUnitPrice} = 房型 + 服务、{@code includedServerCount} = 包厢费已含的免费服务人员数）
     * 与实际计费基数 {@code billableRoomPricePerIncrement}，
     * 事后对账可完整复算「客人看到多少、结台按多少收、免了几名服务人员」。
     */
    public String toSnapshotJson() {
        return "{\"billingUnit\":\"" + billingUnit.name() + "\","
                + "\"roomUnitPrice\":" + roomUnitPrice + ","
                + "\"roomPricePerIncrement\":" + roomPricePerIncrement() + ","
                + "\"billableRoomPricePerIncrement\":" + billableRoomPricePerIncrement() + ","
                + "\"defaultSessionMinutes\":" + defaultSessionMinutes + ","
                + "\"freeWaitMinutes\":" + freeWaitMinutes + ","
                + "\"overtimeRate\":" + effectiveOvertimeRate().toPlainString() + ","
                + "\"incrementMinutes\":" + effectiveIncrementMinutes() + ","
                + "\"roundingDirection\":\"" + effectiveRoundingDirection().name() + "\","
                + "\"serverPricePerInc\":" + serverPricePerInc + ","
                + "\"serverUnitPrice\":" + serverUnitPrice + ","
                + "\"combinedUnitPrice\":" + combinedUnitPrice() + ","
                + "\"roomFeeIncludesServer\":" + roomFeeIncludesServer + ","
                + "\"includedServerCount\":" + (roomFeeIncludesServer ? INCLUDED_SERVER_COUNT : 0) + ","
                + "\"roomTypeCode\":" + quote(appliedRoomTypeCode) + ","
                + "\"roomTypeName\":" + quote(appliedRoomTypeName) + ","
                + "\"roomTypePriceApplied\":" + roomTypePriceApplied + ","
                + "\"unitPriceByRoomType\":" + mapJson(unitPriceByRoomType) + "}";
    }

    /** JSON 字符串字面量（快照手写拼接，只用于本类的短字段；null 写成 null 而不是 "null"）。 */
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 房型单价映射快照：按键排序，保证同一方案每次生成的快照文本一致（可比较、可回归）。 */
    private static String mapJson(Map<String, Long> prices) {
        if (prices == null || prices.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> entry : new TreeMap<>(prices).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(entry.getKey())).append(':').append(entry.getValue());
        }
        return sb.append('}').toString();
    }
}
