package io.openware.platform.order.infra.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Order 总部汇总查询 SQL；只读取 ord_order 权威表，不跨域访问其它表。
 * {@code revenue_amount} 是有效订单的成交总额（{@code total_amount}），{@code paid_amount}
 * 单独保留实际已收快照，避免总部把应收与实收混为一个指标。
 */
@Mapper
public interface OrderOverviewMapper {
    @Select({
            "<script>",
            "SELECT store_id, business_type, currency_code, COUNT(*) AS order_count,",
            "       COALESCE(SUM(total_amount), 0) AS revenue_amount,",
            "       COALESCE(SUM(paid_amount), 0) AS paid_amount",
            "FROM ord_order",
            "WHERE tenant_id = #{tenantId}",
            "  AND status NOT IN ('CANCELLED', 'VOIDED')",
            "  <if test='fromInclusive != null'>AND created_at &gt;= #{fromInclusive}</if>",
            "  <if test='toInclusive != null'>AND created_at &lt;= #{toInclusive}</if>",
            "  <if test='storeIds != null and storeIds.size() &gt; 0'>",
            "    AND store_id IN",
            "    <foreach collection='storeIds' item='storeId' open='(' separator=',' close=')'>#{storeId}</foreach>",
            "  </if>",
            "  <if test='businessType != null and businessType != \"\"'>AND business_type = #{businessType}</if>",
            "GROUP BY store_id, business_type, currency_code",
            "ORDER BY store_id, business_type, currency_code",
            "</script>"
    })
    List<OverviewRow> selectOverview(@Param("tenantId") long tenantId,
                                      @Param("storeIds") List<Long> storeIds,
                                      @Param("businessType") String businessType,
                                      @Param("fromInclusive") LocalDateTime fromInclusive,
                                      @Param("toInclusive") LocalDateTime toInclusive);

    /** SQL 投影，仅供 infra 适配器使用。 */
    class OverviewRow {
        private Long storeId;
        private String businessType;
        private String currencyCode;
        private long orderCount;
        private BigDecimal revenueAmount;
        private BigDecimal paidAmount;

        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public String getBusinessType() { return businessType; }
        public void setBusinessType(String businessType) { this.businessType = businessType; }
        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
        public long getOrderCount() { return orderCount; }
        public void setOrderCount(long orderCount) { this.orderCount = orderCount; }
        public BigDecimal getRevenueAmount() { return revenueAmount; }
        public void setRevenueAmount(BigDecimal revenueAmount) { this.revenueAmount = revenueAmount; }
        public BigDecimal getPaidAmount() { return paidAmount; }
        public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    }
}
