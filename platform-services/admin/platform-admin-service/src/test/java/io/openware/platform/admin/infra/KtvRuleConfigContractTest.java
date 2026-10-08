package io.openware.platform.admin.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.platform.admin.api.ktv.ReservationRuleConfig;
import io.openware.platform.admin.api.ktv.VoidRuleConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KtvRuleConfigContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reservationRulePreservesSource() {
        ReservationRuleConfig config = objectMapper.convertValue(Map.of(
                "id", 1, "storeId", 2, "businessType", "KTV",
                "advanceMinutes", 30, "cancelMinutes", 10, "rescheduleMinutes", 20,
                "version", 1, "idempotencyKey", "reservation-1", "source", "TENANT"),
                ReservationRuleConfig.class);

        assertEquals("TENANT", config.source());
    }

    @Test
    void voidRulePreservesSource() {
        VoidRuleConfig config = objectMapper.convertValue(Map.of(
                "id", 1, "storeId", 2, "businessType", "KTV",
                "requireApproval", true, "version", 1,
                "idempotencyKey", "void-1", "source", "STORE"), VoidRuleConfig.class);

        assertEquals("STORE", config.source());
    }
}
