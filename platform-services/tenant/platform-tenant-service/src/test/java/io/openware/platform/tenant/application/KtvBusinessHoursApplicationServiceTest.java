package io.openware.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.platform.tenant.application.KtvBusinessHoursApplicationService.BusinessHoursView;
import io.openware.platform.tenant.application.KtvBusinessHoursApplicationService.KtvBusinessHours;
import io.openware.platform.tenant.domain.configuration.model.TenantConfigValue;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigRepository;
import io.openware.platform.tenant.domain.store.model.Store;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * KTV 营业时间配置：门店行 → 业态默认行 → 租户默认行 → 缺省 18:00–05:00；跨自然日的时段判定。
 *
 * <p>「预约到店时间必须落在营业时段内」是 C 端/B 端/后台统一的一条规则，判定语义必须锁死在测试里：
 * 左闭右开、跨自然日（18:00–05:00 覆盖凌晨）、全天营业（open == close）。
 *
 * <p>替身是**领域端口**：三层回退各自对应一个端口方法（{@code findStoreValue} /
 * {@code findBusinessValue} / {@code findTenantValue}），回退**顺序**仍在本测试里锁定——
 * 那是业务口径，不属于适配器。
 */
class KtvBusinessHoursApplicationServiceTest {

    private static final long TENANT_ID = 7L;
    private static final long STORE_ID = 1001L;
    private static final String KEY = KtvBusinessHoursApplicationService.CONFIG_KEY;

    private final TenantConfigRepository configs = mock(TenantConfigRepository.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final KtvBusinessHoursApplicationService service =
            new KtvBusinessHoursApplicationService(configs, stores);

    /** 门店事实：归属本租户 + 指定业态（null 表示不关心业态）。 */
    private void store(long storeId, String businessType) {
        when(stores.findById(storeId)).thenReturn(Optional.of(new Store(storeId, TENANT_ID, 1L, "S-" + storeId,
                "store-" + storeId, businessType, "CN", null, "Asia/Shanghai", "CNY", "zh-CN", null,
                "04:00:00", Store.STATUS_ACTIVE)));
        when(stores.findOwnerTenantId(storeId)).thenReturn(Optional.of(TENANT_ID));
    }

    private void noConfig() {
        when(configs.findStoreValue(anyLong(), anyLong(), any(), anyString())).thenReturn(Optional.empty());
        when(configs.findBusinessValue(anyLong(), anyString(), anyString())).thenReturn(Optional.empty());
        when(configs.findTenantValue(anyLong(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void resolvesStoreRowBeforeTenantDefault() {
        when(configs.findStoreValue(TENANT_ID, STORE_ID, null, KEY))
                .thenReturn(Optional.of(TenantConfigValue.store("09:00-22:00")));

        KtvBusinessHours hours = service.resolve(TENANT_ID, STORE_ID);

        assertEquals(LocalTime.of(9, 0), hours.open());
        assertEquals(LocalTime.of(22, 0), hours.close());
        assertEquals("STORE", hours.source());
        assertFalse(hours.crossesMidnight());
    }

    @Test
    void fallsBackToTenantDefaultWhenStoreRowMissing() {
        noConfig();
        when(configs.findTenantValue(TENANT_ID, KEY))
                .thenReturn(Optional.of(TenantConfigValue.tenant("20:00-04:00")));

        KtvBusinessHours hours = service.resolve(TENANT_ID, STORE_ID);

        assertEquals(LocalTime.of(20, 0), hours.open());
        assertEquals("TENANT", hours.source());
    }

    /** 缺省就是 KTV 夜间业态的 18:00 – 次日 05:00（产品口径，不能被改坏）。 */
    @Test
    void fallsBackToKtvNightDefaultWhenNothingConfigured() {
        noConfig();

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
        when(configs.findStoreValue(anyLong(), anyLong(), any(), anyString()))
                .thenReturn(Optional.of(TenantConfigValue.store("晚上六点")));
        when(configs.findTenantValue(anyLong(), anyString()))
                .thenReturn(Optional.of(TenantConfigValue.tenant("25:99-xx")));

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

    /** 业态从门店事实取得（不是调用方传入），据此命中的是 BUSINESS 层而不是 STORE 层。 */
    @Test
    void resolvesBusinessDefaultBeforeTenantDefault() {
        store(STORE_ID, "KTV");
        when(configs.findStoreValue(TENANT_ID, STORE_ID, "KTV", KEY)).thenReturn(Optional.empty());
        when(configs.findBusinessValue(TENANT_ID, "KTV", KEY))
                .thenReturn(Optional.of(TenantConfigValue.business("10:00-23:00")));

        KtvBusinessHours hours = service.resolve(TENANT_ID, STORE_ID);

        assertEquals(LocalTime.of(10, 0), hours.open());
        assertEquals("BUSINESS", hours.source());
    }

    /** 调用方传入的业态与门店事实不一致 → 422（不能按错误的业态去读配置）。 */
    @Test
    void rejectsBusinessTypeMismatchBetweenRequestAndStore() {
        store(STORE_ID, "KTV");

        io.openware.common.exception.ApiException failure =
                org.junit.jupiter.api.Assertions.assertThrows(io.openware.common.exception.ApiException.class,
                        () -> service.resolve(TENANT_ID, STORE_ID, "HOTEL"));

        assertEquals("BUSINESS_TYPE_MISMATCH", failure.getCode());
    }

    @Test
    void batchUpdateRejectsMixedBusinessTypesBeforeWrite() {
        store(1001L, "KTV");
        store(1002L, "HOTEL");

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.update(TENANT_ID, List.of(1001L, 1002L), null,
                        LocalTime.of(9, 0), LocalTime.of(22, 0)));
        assertEquals("BUSINESS_TYPE_MISMATCH", failure.getMessage());
    }

    /** 批量写入门店不属于本租户 → 拒绝，且不写任何配置。 */
    @Test
    void batchUpdateRejectsCrossTenantStoreBeforeWrite() {
        when(stores.findOwnerTenantId(1003L)).thenReturn(Optional.of(999L));

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.update(TENANT_ID, List.of(1003L), null, LocalTime.of(9, 0), LocalTime.of(22, 0)));
        assertEquals("STORE_SCOPE_FORBIDDEN", failure.getMessage());
        org.mockito.Mockito.verify(configs, org.mockito.Mockito.never()).upsert(any());
    }
}
