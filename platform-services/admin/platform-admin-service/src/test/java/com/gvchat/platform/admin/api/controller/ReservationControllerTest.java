package com.gvchat.platform.admin.api.controller;

import com.gvchat.common.exception.ApiException;
import com.gvchat.platform.admin.infra.ReservationDomainClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 后台预约 BFF：取消预约必须带原因（运营代客取消要留操作日志），并且转发前把原因去掉首尾空白。
 * 域内 platform-order-service 也会校验一次，这里先拦是为了不把显然非法的请求打过去。
 */
class ReservationControllerTest {

    private final ReservationDomainClient reservationClient = mock(ReservationDomainClient.class);
    private final ReservationController controller = new ReservationController(reservationClient);

    @Test
    void cancelRequiresReason() {
        ApiException blank = assertThrows(ApiException.class,
                () -> controller.cancel(9L, new ReservationController.CancelRequest("   ")));
        assertEquals(400, blank.getStatus());
        assertEquals("CANCEL_REASON_REQUIRED", blank.getCode());
        assertEquals("取消预约必须填写原因", blank.getMessage());

        ApiException missingBody = assertThrows(ApiException.class, () -> controller.cancel(9L, null));
        assertEquals("CANCEL_REASON_REQUIRED", missingBody.getCode());
        verifyNoInteractions(reservationClient);
    }

    @Test
    void cancelForwardsTrimmedReason() {
        when(reservationClient.cancel(9L, "客人临时有事")).thenReturn(Map.of());

        controller.cancel(9L, new ReservationController.CancelRequest(" 客人临时有事 "));

        verify(reservationClient).cancel(9L, "客人临时有事");
    }
}
