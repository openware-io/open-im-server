package io.openware.platform.order.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 房态三字段派生规则：空闲/已入住/待清扫/已预留/维修中。 */
class HotelRoomStatePolicyTest {

    @Test
    void boardLabelDerivedFromThreeStatusFields() {
        assertEquals("空闲", HotelRoomStatePolicy.boardLabel("VACANT", "CLEAN", "NONE", null));
        assertEquals("空闲", HotelRoomStatePolicy.boardLabel("VACANT", "INSPECTED", "NONE", null));
        assertEquals("已入住", HotelRoomStatePolicy.boardLabel("OCCUPIED", "CLEAN", "NONE", null));
        assertEquals("待清扫", HotelRoomStatePolicy.boardLabel("VACANT", "DIRTY", "NONE", null));
        assertEquals("待清扫", HotelRoomStatePolicy.boardLabel("VACANT", "CLEANING", "NONE", null));
        assertEquals("已预留", HotelRoomStatePolicy.boardLabel("VACANT", "CLEAN", "NONE", "RESERVED"));
        assertEquals("已预留", HotelRoomStatePolicy.boardLabel("VACANT", "CLEAN", "NONE", "GROUP_BLOCK"));
        assertEquals("已预留", HotelRoomStatePolicy.boardLabel("VACANT", "CLEAN", "NONE", "HOUSE_USE"));
        assertEquals("维修中", HotelRoomStatePolicy.boardLabel("VACANT", "CLEAN", "OUT_OF_ORDER", null));
        assertEquals("维修中", HotelRoomStatePolicy.boardLabel("VACANT", "CLEAN", "OUT_OF_SERVICE", null));
    }

    @Test
    void outOfOrderWinsOverReservationAndOccupancy() {
        assertEquals("维修中", HotelRoomStatePolicy.boardLabel("VACANT", "CLEAN", "OUT_OF_ORDER", "RESERVED"));
        assertEquals("maintenance",
                HotelRoomStatePolicy.boardTone("VACANT", "CLEAN", "OUT_OF_SERVICE", null));
        assertEquals("reserved",
                HotelRoomStatePolicy.boardTone("VACANT", "CLEAN", "NONE", "GROUP_BLOCK"));
        assertEquals("dirty", HotelRoomStatePolicy.boardTone("VACANT", "CLEANING", "NONE", null));
        assertEquals("active", HotelRoomStatePolicy.boardTone("VACANT", "INSPECTED", "NONE", null));
    }

    @Test
    void dirtyOrMaintenanceOrHeldRoomCannotCheckIn() {
        assertNull(HotelRoomStatePolicy.checkInBlockedCode("VACANT", "CLEAN", "NONE", null));
        assertNull(HotelRoomStatePolicy.checkInBlockedCode("VACANT", "INSPECTED", "NONE", null));
        assertEquals("ROOM_HOUSEKEEPING_PENDING",
                HotelRoomStatePolicy.checkInBlockedCode("VACANT", "DIRTY", "NONE", null));
        assertEquals("ROOM_HOUSEKEEPING_PENDING",
                HotelRoomStatePolicy.checkInBlockedCode("VACANT", "CLEANING", "NONE", null));
        assertEquals("ROOM_OUT_OF_ORDER",
                HotelRoomStatePolicy.checkInBlockedCode("VACANT", "CLEAN", "OUT_OF_ORDER", null));
        assertEquals("ROOM_HELD",
                HotelRoomStatePolicy.checkInBlockedCode("VACANT", "CLEAN", "NONE", "RESERVED"));
        assertEquals("ROOM_OCCUPIED",
                HotelRoomStatePolicy.checkInBlockedCode("OCCUPIED", "CLEAN", "NONE", null));
        assertFalse(HotelRoomStatePolicy.sellable("VACANT", "DIRTY", "NONE", null));
        assertTrue(HotelRoomStatePolicy.sellable("VACANT", "INSPECTED", "NONE", null));
    }

    @Test
    void housekeepingTransitionsOnlyAdvanceForward() {
        assertTrue(HotelRoomStatePolicy.isValidHousekeepingTransition("DIRTY", "CLEANING"));
        assertTrue(HotelRoomStatePolicy.isValidHousekeepingTransition("CLEANING", "CLEAN"));
        assertTrue(HotelRoomStatePolicy.isValidHousekeepingTransition("CLEAN", "INSPECTED"));
        assertTrue(HotelRoomStatePolicy.isValidHousekeepingTransition("CLEAN", "DIRTY"));
        assertFalse(HotelRoomStatePolicy.isValidHousekeepingTransition("DIRTY", "CLEAN"));
        assertFalse(HotelRoomStatePolicy.isValidHousekeepingTransition("DIRTY", "INSPECTED"));
        assertTrue(HotelRoomStatePolicy.isValidHousekeepingTransition("CLEAN", "CLEAN"));
    }

    @Test
    void unknownStatusOnlyExposesThreeFields() {
        assertTrue(HotelRoomStatePolicy.HOUSEKEEPING_STATUSES.contains("DIRTY"));
        assertTrue(HotelRoomStatePolicy.OUT_OF_ORDER_STATUSES.contains("OUT_OF_SERVICE"));
        assertTrue(HotelRoomStatePolicy.GUEST_HOLD_REASONS.contains("HOUSE_USE"));
        assertTrue(HotelRoomStatePolicy.GUEST_HOLD_REASONS.contains("LOCKED"));
        assertTrue(HotelRoomStatePolicy.OUT_OF_ORDER_HOLD_REASONS.contains("MAINTENANCE"));
    }
}
