package io.openware.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.platform.tenant.application.KtvBusinessHoursApplicationService.BusinessHoursView;
import io.openware.platform.tenant.application.KtvBusinessHoursApplicationService.KtvBusinessHours;
import io.openware.platform.tenant.infra.persistence.mapper.InternalTenantConfigMapper;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * KTV 营业时间配置：门店行 → 租户默认行 → 缺省 18:00–05:00；跨自然日的时段判定。
 *
 * <p>「预约到店时间必须落在营业时段内」是 C 端/B 端/后台统一的一条规则，判定语义必须锁死在测试里：
 * 左闭右开、跨自然日（18:00–05:00 覆盖凌晨）、全天营业（open == close）。
 */
class KtvBusinessHoursApplicationServiceTest {

    private static final long TENANT_ID = 7L;
    private static final long STORE_ID = 1001L;

    private final InternalTenantConfigMapper mapper = mock(InternalTenantConfigMapper.class);
    private final KtvBusinessHoursApplicationService service =
            new KtvBusinessHoursApplicationService(mapper);

    @Test
    void resolvesStoreRowBeforeTenantDefault() {
        when(mapper.selectStoreConfigValue(TENANT_ID, STORE_ID, KtvBusinessHoursApplicationService.CONFIG_KEY))
                .thenReturn("09:00-22:00");

        KtvBusinessHours hours = service.resolve(TENANT_ID, STORE_ID);

        assertEquals(LocalTime.of(9, 0), hours.open());
        assertEquals(LocalTime.of(22, 0), hours.close());
        assertEquals("STORE", hours.source());
        assertFalse(hours.crossesMidnight());
    }

    @Test
    void fallsBackToTenantDefaultWhenStoreRowMissing() {
        when(mapper.selectStoreConfigValue(anyLong(), anyLong(), anyString())).thenReturn(null);
        when(mapper.selectTenantConfigValue(TENANT_ID, KtvBusinessHoursApplicationService.CONFIG_KEY))
                .thenReturn("20:00-04:00");

        KtvBusinessHours hours = service.resolve(TENANT_ID, STORE_ID);

        assertEquals(LocalTime.of(20, 0), hours.open());
        assertEquals("TENANT", hours.source());
    }

    /** 缺省就是 KTV 夜间业态的 18:00 – 次日 05:00（产品口径，不能被改坏）。 */
    @Test
    void fallsBackToKtvNightDefaultWhenNothingConfigured() {
        when(mapper.selectStoreConfigValue(anyLong(), anyLong(), anyString())).thenReturn(null);
        when(mapper.selectTenantConfigValue(anyLong(), anyString())).thenReturn(null);

        KtvBusinessHours hours = service.resolve(TENANT_ID, STORE_ID);

        assertEquals(LocalTime.of(18, 0), hours.open());
        assertEquals(LocalTime.of(5, 0), hours.close());
        assertEquals("DEFAULT", hours.source());
        assertTrue(hours.crossesMidnight());
        assertEquals("18:00 – 次日 05:00", hours.displayText());
    }

    /** 非法配置（写坏了）不能把预约入口打挂：按缺省处理，读路径不抛异常。 */
    @Test
    void invalidConfigFallsBackToDefaultInsteadOfThrowing() {
        when(mapper.selectStoreConfigValue(anyLong(), anyLong(), anyString())).thenReturn("晚上六点");
        when(mapper.selectTenantConfigValue(anyLong(), anyString())).thenReturn("25:99-xx");

        KtvBusinessHours hours = service.resolve(TENANT_ID, STORE_ID);

        assertEquals("DEFAULT", hours.source());
    }

    /** 跨自然日：18:00–05:00 覆盖当天 18:00 之后与次日 05:00 之前；05:00 整点已打烊（左闭右开）。 */
    @Test
    void containsForMidnightCrossingWindow() {
        KtvBusinessHours hours = new KtvBusinessHours(LocalTime.of(18, 0), LocalTime.of(5, 0), "TENANT");

        assertTrue(hours.contains(LocalTime.of(18, 0)));
        assertTrue(hours.contains(LocalTime.of(23, 59)));
        assertTrue(hours.contains(LocalTime.of(0, 0)));
        assertTrue(hours.contains(LocalTime.of(4, 59)));
        assertFalse(hours.contains(LocalTime.of(5, 0)));
        assertFalse(hours.contains(LocalTime.of(17, 59)));
        assertFalse(hours.contains(null));
    }

    /** 同一天区间：09:00–22:00（午餐/午市门店）左闭右开。 */
    @Test
    void containsForSameDayWindow() {
        KtvBusinessHours hours = new KtvBusinessHours(LocalTime.of(9, 0), LocalTime.of(22, 0), "STORE");

        assertTrue(hours.contains(LocalTime.of(9, 0)));
        assertTrue(hours.contains(LocalTime.of(21, 59)));
        assertFalse(hours.contains(LocalTime.of(22, 0)));
        assertFalse(hours.contains(LocalTime.of(8, 59)));
    }

    /** open == close：全天营业（例如 24 小时门店）。 */
    @Test
    void openEqualsCloseMeansAllDay() {
        KtvBusinessHours hours = new KtvBusinessHours(LocalTime.of(0, 0), LocalTime.of(0, 0), "TENANT");

        assertTrue(hours.allDay());
        assertTrue(hours.contains(LocalTime.of(3, 0)));
        assertEquals("全天营业", hours.displayText());
    }

    @Test
    void formatAndViewUseHhMmStrings() {
        assertEquals("18:00-05:00", KtvBusinessHoursApplicationService.format(LocalTime.of(18, 0), LocalTime.of(5, 0)));

        BusinessHoursView view = BusinessHoursView.of(STORE_ID,
                new KtvBusinessHours(LocalTime.of(18, 0), LocalTime.of(5, 0), "DEFAULT"));

        assertEquals("18:00", view.openTime());
        assertEquals("05:00", view.closeTime());
        assertTrue(view.crossesMidnight());
        assertEquals("18:00 – 次日 05:00", view.displayText());
    }
}
