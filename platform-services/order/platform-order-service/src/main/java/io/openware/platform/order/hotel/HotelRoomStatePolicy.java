package io.openware.platform.order.hotel;

import java.util.Set;

/**
 * 房态三字段的唯一派生规则：占用（occupancy）、清扫（housekeeping）、不可售（out-of-order）。
 *
 * <p>业务代码只允许从三组字段派生展示态/可售/可入住，不再读取单一 room.status。
 */
public final class HotelRoomStatePolicy {

    public static final String OCCUPANCY_VACANT = "VACANT";
    public static final String OCCUPANCY_OCCUPIED = "OCCUPIED";

    public static final String HOUSEKEEPING_CLEAN = "CLEAN";
    public static final String HOUSEKEEPING_DIRTY = "DIRTY";
    public static final String HOUSEKEEPING_CLEANING = "CLEANING";
    public static final String HOUSEKEEPING_INSPECTED = "INSPECTED";

    public static final String OUT_OF_ORDER_NONE = "NONE";
    public static final String OUT_OF_ORDER = "OUT_OF_ORDER";
    public static final String OUT_OF_SERVICE = "OUT_OF_SERVICE";

    public static final String HOLD_RESERVED = "RESERVED";
    public static final String HOLD_GROUP_BLOCK = "GROUP_BLOCK";
    public static final String HOLD_HOUSE_USE = "HOUSE_USE";
    public static final String HOLD_LOCKED = "LOCKED";
    public static final String HOLD_MAINTENANCE = "MAINTENANCE";
    public static final String HOLD_OUT_OF_SERVICE = "OUT_OF_SERVICE";

    public static final String TONE_ACTIVE = "active";
    public static final String TONE_OCCUPIED = "occupied";
    public static final String TONE_DIRTY = "dirty";
    public static final String TONE_RESERVED = "reserved";
    public static final String TONE_MAINTENANCE = "maintenance";

    public static final Set<String> HOUSEKEEPING_STATUSES =
            Set.of(HOUSEKEEPING_CLEAN, HOUSEKEEPING_DIRTY, HOUSEKEEPING_CLEANING, HOUSEKEEPING_INSPECTED);
    public static final Set<String> OUT_OF_ORDER_STATUSES =
            Set.of(OUT_OF_ORDER_NONE, OUT_OF_ORDER, OUT_OF_SERVICE);
    /** 客房保留/锁定类原因：这些原因让房间进入“已预留”，不可售。 */
    public static final Set<String> GUEST_HOLD_REASONS =
            Set.of(HOLD_RESERVED, HOLD_GROUP_BLOCK, HOLD_HOUSE_USE, HOLD_LOCKED);
    /** 不可售原因：由维修/停售服务维护，同时调整房量。 */
    public static final Set<String> OUT_OF_ORDER_HOLD_REASONS =
            Set.of(HOLD_MAINTENANCE, HOLD_OUT_OF_SERVICE);
    private static final Set<String> SELLABLE_HOUSEKEEPING =
            Set.of(HOUSEKEEPING_CLEAN, HOUSEKEEPING_INSPECTED);

    private HotelRoomStatePolicy() {
    }

    /** 前台房态卡展示态：维修中 / 已预留 / 已入住 / 待清扫 / 空闲。 */
    public static String boardLabel(String occupancyStatus, String housekeepingStatus,
                                    String outOfOrderStatus, String holdReason) {
        if (outOfOrder(outOfOrderStatus)) {
            return "维修中";
        }
        if (guestHeld(holdReason)) {
            return "已预留";
        }
        if (OCCUPANCY_OCCUPIED.equals(occupancyStatus)) {
            return "已入住";
        }
        if (HOUSEKEEPING_DIRTY.equals(housekeepingStatus) || HOUSEKEEPING_CLEANING.equals(housekeepingStatus)) {
            return "待清扫";
        }
        return "空闲";
    }

    /** 房态卡颜色/筛选用的稳定 key：active/occupied/dirty/reserved/maintenance。 */
    public static String boardTone(String occupancyStatus, String housekeepingStatus,
                                   String outOfOrderStatus, String holdReason) {
        if (outOfOrder(outOfOrderStatus)) {
            return TONE_MAINTENANCE;
        }
        if (guestHeld(holdReason)) {
            return TONE_RESERVED;
        }
        if (OCCUPANCY_OCCUPIED.equals(occupancyStatus)) {
            return TONE_OCCUPIED;
        }
        if (HOUSEKEEPING_DIRTY.equals(housekeepingStatus) || HOUSEKEEPING_CLEANING.equals(housekeepingStatus)) {
            return TONE_DIRTY;
        }
        return TONE_ACTIVE;
    }

    /** 可售：空闲、清扫完成、未被维修/停售、未被保留。 */
    public static boolean sellable(String occupancyStatus, String housekeepingStatus,
                                   String outOfOrderStatus, String holdReason) {
        return checkInBlockedCode(occupancyStatus, housekeepingStatus, outOfOrderStatus, holdReason) == null;
    }

    /**
     * 入住拦截原因；返回 null 表示可以入住。
     * 脏房、维修房、已预留房间都不能入住，必须由前台先处理。
     */
    public static String checkInBlockedCode(String occupancyStatus, String housekeepingStatus,
                                            String outOfOrderStatus, String holdReason) {
        if (OCCUPANCY_OCCUPIED.equals(occupancyStatus)) {
            return "ROOM_OCCUPIED";
        }
        if (outOfOrder(outOfOrderStatus)) {
            return "ROOM_OUT_OF_ORDER";
        }
        if (guestHeld(holdReason)) {
            return "ROOM_HELD";
        }
        if (!SELLABLE_HOUSEKEEPING.contains(housekeepingStatus)) {
            return "ROOM_HOUSEKEEPING_PENDING";
        }
        return null;
    }

    /** 清扫状态流转：DIRTY→CLEANING→CLEAN→INSPECTED；空闲脏房可手动重开。 */
    public static boolean isValidHousekeepingTransition(String from, String to) {
        if (from == null || to == null || !HOUSEKEEPING_STATUSES.contains(to)) {
            return false;
        }
        if (from.equals(to)) {
            return true;
        }
        return switch (from) {
            case HOUSEKEEPING_DIRTY -> HOUSEKEEPING_CLEANING.equals(to);
            case HOUSEKEEPING_CLEANING -> HOUSEKEEPING_CLEAN.equals(to) || HOUSEKEEPING_DIRTY.equals(to);
            case HOUSEKEEPING_CLEAN -> HOUSEKEEPING_INSPECTED.equals(to) || HOUSEKEEPING_DIRTY.equals(to);
            case HOUSEKEEPING_INSPECTED -> HOUSEKEEPING_CLEAN.equals(to) || HOUSEKEEPING_DIRTY.equals(to);
            default -> false;
        };
    }

    public static boolean outOfOrder(String outOfOrderStatus) {
        return OUT_OF_ORDER.equals(outOfOrderStatus) || OUT_OF_SERVICE.equals(outOfOrderStatus);
    }

    public static boolean guestHeld(String holdReason) {
        return holdReason != null && GUEST_HOLD_REASONS.contains(holdReason);
    }
}
