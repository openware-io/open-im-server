package com.gvchat.platform.order.application;

import com.gvchat.platform.order.domain.ktv.model.KtvRoundingDirection;

/**
 * 服务人员点单单位时间计费（纯函数，无副作用）。
 * 公式：M = 舍入方向(秒→分钟)；n = 舍入方向(分钟→块)；费 = price_per_inc × n（整数无浮点）。
 * 默认 CONSUMER_FAVOR（让利）：M = floor(D_sec/60)，n = ceil(M/inc)，即「向下抹零」。
 *
 * <p>块数换算统一委托 {@link KtvRoundingDirection#secondsToBlocks(long, int)}：包厢房费
 * （{@code KtvRoomFeeCalculator}）与服务人员费必须共用同一套舍入算法，禁止两处各写一套。
 */
public final class UnitTimeFeeCalculator {
    private UnitTimeFeeCalculator() {}

    /** 计费分钟数 M（默认 CONSUMER_FAVOR：floor）。 */
    public static int calculateMinutes(long durationSeconds) {
        return calculateMinutes(durationSeconds, KtvRoundingDirection.CONSUMER_FAVOR);
    }

    /** 计费分钟数 M，按舍入方向换算。 */
    public static int calculateMinutes(long durationSeconds, KtvRoundingDirection direction) {
        return direction.secondsToMinutes(Math.max(0L, durationSeconds));
    }

    /** 计费块数 n（默认 CONSUMER_FAVOR：ceil）。 */
    public static int calculateBlocks(long durationSeconds, int incrementMinutes) {
        return calculateBlocks(durationSeconds, incrementMinutes, KtvRoundingDirection.CONSUMER_FAVOR);
    }

    /** 计费块数 n，按舍入方向换算。 */
    public static int calculateBlocks(long durationSeconds, int incrementMinutes, KtvRoundingDirection direction) {
        return direction.secondsToBlocks(durationSeconds, incrementMinutes);
    }

    /** 应付金额（最小货币单位整数）= price_per_inc × n（默认 CONSUMER_FAVOR）。 */
    public static long calculateTotal(long durationSeconds, int incrementMinutes, long pricePerInc) {
        return calculateTotal(durationSeconds, incrementMinutes, pricePerInc, KtvRoundingDirection.CONSUMER_FAVOR);
    }

    /** 应付金额（最小货币单位整数）= price_per_inc × n，按舍入方向换算。 */
    public static long calculateTotal(long durationSeconds, int incrementMinutes, long pricePerInc, KtvRoundingDirection direction) {
        return pricePerInc * calculateBlocks(durationSeconds, incrementMinutes, direction);
    }
}
