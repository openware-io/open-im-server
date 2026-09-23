package io.openware.platform.order.domain.ktv.model;

/**
 * 服务人员点单计时舍入方向（KTV_BUSINESS_01 §5.3，默认让利消费者）。
 */
public enum KtvRoundingDirection {
    /** 默认，让利消费者：秒→分钟向下取整，分钟→块向上取整。 */
    CONSUMER_FAVOR,
    /** 从严：秒→分钟向上取整，分钟→块向上取整。 */
    ROUND_UP,
    /** 最让利：秒→分钟向下取整，分钟→块向下取整。 */
    FLOOR_BLOCK;

    /** 解析持久化字符串，未知或空回退默认 {@link #CONSUMER_FAVOR}。 */
    public static KtvRoundingDirection fromCode(String code) {
        if (code == null || code.isBlank()) {
            return CONSUMER_FAVOR;
        }
        for (KtvRoundingDirection v : values()) {
            if (v.name().equals(code)) {
                return v;
            }
        }
        return CONSUMER_FAVOR;
    }

    /** 秒 → 计费分钟。 */
    public int secondsToMinutes(long seconds) {
        return switch (this) {
            case ROUND_UP -> (int) Math.ceil(seconds / 60.0);
            default -> (int) (seconds / 60);
        };
    }

    /** 分钟 → 计费块数（递增粒度分钟）。 */
    public int minutesToBlocks(int minutes, int incrementMinutes) {
        int inc = incrementMinutes > 0 ? incrementMinutes : 30;
        return switch (this) {
            case FLOOR_BLOCK -> minutes / inc;
            default -> (int) Math.ceil(minutes / (double) inc);
        };
    }

    /**
     * 秒 → 计费块数（唯一入口：包厢房费与服务人员费必须走同一算法，禁止各写一套）。
     * 两步复合：先按本方向把秒换算成计费分钟，再按本方向把分钟换算成递增块数。
     * 默认 {@link #CONSUMER_FAVOR} 即「秒向下抹零 + 不足一块按一块」；示例：33 秒 → 0 分钟 → 0 块（让利），
     * 25 分钟 → 25 分钟 → 1 块，31 分钟 → 31 分钟 → 2 块。
     */
    public int secondsToBlocks(long seconds, int incrementMinutes) {
        return minutesToBlocks(secondsToMinutes(Math.max(0L, seconds)), incrementMinutes);
    }
}
