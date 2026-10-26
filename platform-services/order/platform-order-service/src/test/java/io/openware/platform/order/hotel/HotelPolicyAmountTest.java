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

    @Test
    void cancellationFreeWindowAndOneNightFeeFollowPolicy() {
        HotelPolicyPo policy = new HotelPolicyPo();
        policy.setCancelPolicy("FREE_UNTIL_24H");
        HotelBookingPo booking = booking(java.time.LocalDate.now().plusDays(3));

        HotelPolicyApplicationService.CancellationQuote free =
                service.cancellationQuote(booking, policy, java.time.LocalDateTime.now());
        assertEquals(0L, free.feeMinor());
        assertEquals(100_000L, free.refundMinor());

        booking.setCheckInDate(java.time.LocalDate.now());
        HotelPolicyApplicationService.CancellationQuote late =
                service.cancellationQuote(booking, policy, java.time.LocalDateTime.now());
        assertEquals(50_000L, late.feeMinor());
        assertEquals(50_000L, late.refundMinor());
    }

    @Test
    void nonRefundableAndFirstNightCancellationAreDeterministic() {
        HotelBookingPo booking = booking(java.time.LocalDate.now().plusDays(3));

        HotelPolicyPo nonRefundable = new HotelPolicyPo();
        nonRefundable.setCancelPolicy("NON_REFUNDABLE");
        assertEquals(100_000L,
                service.cancellationQuote(booking, nonRefundable, java.time.LocalDateTime.now()).feeMinor());

        HotelPolicyPo firstNight = new HotelPolicyPo();
        firstNight.setCancelPolicy("FIRST_NIGHT");
        HotelPolicyApplicationService.CancellationQuote quote =
                service.cancellationQuote(booking, firstNight, java.time.LocalDateTime.now());
        assertEquals(50_000L, quote.feeMinor());
        assertEquals(50_000L, quote.refundMinor());
    }

    private HotelBookingPo booking(java.time.LocalDate checkIn) {
        HotelBookingPo booking = new HotelBookingPo();
        booking.setAmountMinor(100_000L);
        booking.setNights(2);
        booking.setCheckInDate(checkIn);
        return booking;
    }
}
