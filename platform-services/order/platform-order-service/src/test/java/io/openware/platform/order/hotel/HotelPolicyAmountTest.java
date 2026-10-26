package io.openware.platform.order.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HotelPolicyAmountTest {

    private final HotelPolicyApplicationService service = new HotelPolicyApplicationService(null, null);

    @Test
    void fixedDepositWinsAndNeverExceedsTotal() {
        HotelPolicyPo policy = new HotelPolicyPo();
        policy.setStayDepositMinor(20_000L);
        policy.setDepositRatioBps(3_000);

        assertEquals(20_000L, service.depositAmount(89_900L, policy));
        assertEquals(10_000L, service.depositAmount(10_000L, policy));
    }

    @Test
    void ratioDepositRoundsAndHasOneMinorFloor() {
        HotelPolicyPo policy = new HotelPolicyPo();
        policy.setStayDepositMinor(0L);
        policy.setDepositRatioBps(3_000);

        assertEquals(26_970L, service.depositAmount(89_900L, policy));
        assertEquals(1L, service.depositAmount(1L, policy));
        assertEquals(0L, service.depositAmount(0L, policy));
    }
}
