package io.openware.common.payment.infra.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Payment 域总部只读汇总查询。只访问本域支付交易事实表，不跨库读取订单或客户数据。 */
@Mapper
public interface PaymentOverviewMapper {
  @Select({"<script>",
      "SELECT provider, currency_code, COUNT(*) AS transaction_count, COALESCE(SUM(amount), 0) AS amount",
      "FROM pay_transaction WHERE tenant_id = #{tenantId} AND status = 'SUCCEEDED'",
      "<if test='storeIds != null and !storeIds.isEmpty()'> AND store_id IN",
      "<foreach item='storeId' collection='storeIds' open='(' separator=',' close=')'>#{storeId}</foreach>",
      "</if>",
      "<if test='from != null'> AND occurred_at &gt;= #{from}</if>",
      "<if test='to != null'> AND occurred_at &lt;= #{to}</if>",
      "GROUP BY provider, currency_code ORDER BY currency_code, provider",
      "</script>"})
  List<PaymentOverviewRow> selectSucceeded(@Param("tenantId") Long tenantId,
      @Param("storeIds") List<Long> storeIds, @Param("from") LocalDateTime from,
      @Param("to") LocalDateTime to);

  @Select({"<script>",
      "SELECT currency_code, COUNT(*) AS refund_count,",
      "COALESCE(SUM(COALESCE(approved_amount, requested_amount, 0)), 0) AS amount",
      "FROM pay_refund WHERE tenant_id = #{tenantId} AND status = 'REFUNDED'",
      "<if test='storeIds != null and !storeIds.isEmpty()'> AND store_id IN",
      "<foreach item='storeId' collection='storeIds' open='(' separator=',' close=')'>#{storeId}</foreach>",
      "</if>",
      "<if test='from != null'> AND updated_at &gt;= #{from}</if>",
      "<if test='to != null'> AND updated_at &lt;= #{to}</if>",
      "GROUP BY currency_code ORDER BY currency_code",
      "</script>"})
  List<RefundOverviewRow> selectRefunded(@Param("tenantId") Long tenantId,
      @Param("storeIds") List<Long> storeIds, @Param("from") LocalDateTime from,
      @Param("to") LocalDateTime to);

  class PaymentOverviewRow {
    private String provider;
    private String currencyCode;
    private long transactionCount;
    private BigDecimal amount;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public long getTransactionCount() { return transactionCount; }
    public void setTransactionCount(long transactionCount) { this.transactionCount = transactionCount; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
  }

  class RefundOverviewRow {
    private String currencyCode;
    private long refundCount;
    private BigDecimal amount;

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public long getRefundCount() { return refundCount; }
    public void setRefundCount(long refundCount) { this.refundCount = refundCount; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
  }
}
