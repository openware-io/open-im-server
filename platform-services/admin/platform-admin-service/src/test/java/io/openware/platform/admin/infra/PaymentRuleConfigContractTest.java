package io.openware.platform.admin.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.platform.admin.api.ktv.PaymentRuleConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Payment 域规则查询返回的 source 必须能穿过 Admin BFF DTO。 */
class PaymentRuleConfigContractTest {

    @Test
    void convertsEffectiveRuleSourceFromPaymentDomain() {
        PaymentRuleConfig config = new ObjectMapper().convertValue(Map.of(
                "storeId", 100L,
                "businessType", "KTV",
                "approvalThreshold", 0,
                "offlineRefundEnabled", true,
                "closingMinute", 300,
                "version", 1,
                "refundVersion", 1,
                "closingVersion", 1,
                "idempotencyKey", "test-key",
                "source", "STORE"), PaymentRuleConfig.class);

        assertEquals("STORE", config.source());
    }
}
