package io.openware.platform.order.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.openware.platform.order.domain.order.repository.DailySerialRepository;
import io.openware.platform.order.infra.persistence.mapper.DailySerialMapper;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * 单据号序号适配器的两件关键翻译（真实适配器 + mock mapper）。
 *
 * <p><b>为什么必须有测试</b>：应用层的重试语义依赖端口把「建行撞唯一键」表达成 {@code false}
 * （{@code DailySerialNumberGenerator} 的并发首单重试路径）。若适配器把
 * {@code DuplicateKeyException} 直接放出去，那条重试路径会退化成失败关闭——单测里看不出来
 * （应用层测试 mock 的是端口，不是适配器），只会在并发首单时表现为「发不出号」。
 *
 * <p>另外钉住三条语句都**忽略租户行拦截并显式带 tenant_id**：发号发生在单据落库之前，
 * 调用上下文可能缺失；少了显式租户条件会静默重发 0001（撞唯一键）或查不到行。
 */
class DailySerialRepositoryAdapterTest {

  private static final long TENANT_ID = 7L;
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 19);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 19, 12, 0);

  private final DailySerialMapper mapper = mock(DailySerialMapper.class);
  private final DailySerialRepository adapter = new DailySerialRepositoryAdapter(mapper);

  /** 自增语句：1 行 = 命中（行已存在），0 行 = 需要先建行。 */
  @Test
  void incrementIfPresentMapsRowCountToBoolean() {
    when(mapper.incrementIfPresent(TENANT_ID, "ORDER", BUSINESS_DATE, NOW)).thenReturn(1);
    assertTrue(adapter.incrementIfPresent(TENANT_ID, "ORDER", BUSINESS_DATE, NOW));

    when(mapper.incrementIfPresent(TENANT_ID, "ORDER", BUSINESS_DATE, NOW)).thenReturn(0);
    assertFalse(adapter.incrementIfPresent(TENANT_ID, "ORDER", BUSINESS_DATE, NOW));
  }

  /** 建行成功：本次拿到当日第 1 单。 */
  @Test
  void insertFirstIfAbsentReturnsTrueWhenRowCreated() {
    when(mapper.insertFirst(TENANT_ID, "RESERVATION", BUSINESS_DATE, NOW)).thenReturn(1);

    assertTrue(adapter.insertFirstIfAbsent(TENANT_ID, "RESERVATION", BUSINESS_DATE, NOW));
  }

  /**
   * 并发首单被抢：唯一键冲突必须被翻译成 {@code false}（而不是把 DAO 异常抛给应用层），
   * 调用方据此在下一轮重试自增——这是「并发首单仍能发出正确序号」的唯一路径。
   */
  @Test
  void insertFirstIfAbsentReturnsFalseOnDuplicateKey() {
    when(mapper.insertFirst(TENANT_ID, "ORDER", BUSINESS_DATE, NOW))
        .thenThrow(new DuplicateKeyException("uk_ord_daily_serial_key"));

    assertFalse(adapter.insertFirstIfAbsent(TENANT_ID, "ORDER", BUSINESS_DATE, NOW));
  }

  /** 读回序号：空行返回空 Optional（调用方按异常态失败关闭，绝不当成 0/1 发出去）。 */
  @Test
  void currentSequenceReturnsOptional() {
    when(mapper.selectCurrentSeq(TENANT_ID, "ORDER", BUSINESS_DATE)).thenReturn(42L);
    assertEquals(Optional.of(42L), adapter.currentSequence(TENANT_ID, "ORDER", BUSINESS_DATE));

    when(mapper.selectCurrentSeq(TENANT_ID, "RESERVATION", BUSINESS_DATE)).thenReturn(null);
    assertTrue(adapter.currentSequence(TENANT_ID, "RESERVATION", BUSINESS_DATE).isEmpty());
  }

  /** 三条语句都忽略租户行拦截并显式带 tenant_id / biz_type / business_date。 */
  @Test
  void mapperStatementsIgnoreTenantLineAndCarryTenantExplicitly() throws Exception {
    String increment = updateStatement("incrementIfPresent", Long.class, String.class, LocalDate.class,
        LocalDateTime.class);
    String normalized = normalize(increment);
    assertTrue(normalized.contains("UPDATE ORD_DAILY_SERIAL SET CURRENT_SEQ = CURRENT_SEQ + 1"), increment);
    assertTrue(normalized.contains("WHERE TENANT_ID = #{TENANTID} AND BIZ_TYPE = #{BIZTYPE} "
        + "AND BUSINESS_DATE = #{BUSINESSDATE}"), increment);
    assertTrue(normalized.contains("UPDATED_AT = #{NOW}"), increment);

    String insert = insertStatement("insertFirst", Long.class, String.class, LocalDate.class,
        LocalDateTime.class);
    String normalizedInsert = normalize(insert);
    assertTrue(normalizedInsert.contains("INSERT INTO ORD_DAILY_SERIAL"), insert);
    assertTrue(normalizedInsert.contains("CURRENT_SEQ"), insert);
    assertTrue(normalizedInsert.contains("VALUES (#{TENANTID}, #{BIZTYPE}, #{BUSINESSDATE}, 1"),
        "建行即占用当日第 1 单: " + insert);

    String current = selectStatement("selectCurrentSeq", Long.class, String.class, LocalDate.class);
    String normalizedCurrent = normalize(current);
    assertTrue(normalizedCurrent.contains("SELECT CURRENT_SEQ FROM ORD_DAILY_SERIAL"), current);
    assertTrue(normalizedCurrent.contains("WHERE TENANT_ID = #{TENANTID} AND BIZ_TYPE = #{BIZTYPE} "
        + "AND BUSINESS_DATE = #{BUSINESSDATE}"), current);

    for (String method : new String[] {"incrementIfPresent", "insertFirst", "selectCurrentSeq"}) {
      Method target = methodByName(method);
      InterceptorIgnore ignore = target.getAnnotation(InterceptorIgnore.class);
      assertNotNull(ignore, method + " 必须忽略租户行拦截（发号时可能没有上下文）");
      assertEquals("true", ignore.tenantLine(), method + " 必须显式忽略租户行");
    }

    // 适配器确实是「原样委托」，不是自己拼 SQL。
    when(mapper.selectCurrentSeq(TENANT_ID, "ORDER", BUSINESS_DATE)).thenReturn(1L);
    adapter.currentSequence(TENANT_ID, "ORDER", BUSINESS_DATE);
    verify(mapper).selectCurrentSeq(TENANT_ID, "ORDER", BUSINESS_DATE);
  }

  private static Method methodByName(String name) {
    for (Method method : DailySerialMapper.class.getMethods()) {
      if (method.getName().equals(name)) {
        return method;
      }
    }
    throw new AssertionError("DailySerialMapper 缺少语句: " + name);
  }

  private static String updateStatement(String method, Class<?>... parameters) throws Exception {
    Method target = DailySerialMapper.class.getMethod(method, parameters);
    Update annotation = target.getAnnotation(Update.class);
    assertNotNull(annotation, method + " 必须仍然是手写 @Update");
    return String.join(" ", annotation.value());
  }

  private static String insertStatement(String method, Class<?>... parameters) throws Exception {
    Method target = DailySerialMapper.class.getMethod(method, parameters);
    Insert annotation = target.getAnnotation(Insert.class);
    assertNotNull(annotation, method + " 必须仍然是手写 @Insert");
    return String.join(" ", annotation.value());
  }

  private static String selectStatement(String method, Class<?>... parameters) throws Exception {
    Method target = DailySerialMapper.class.getMethod(method, parameters);
    Select annotation = target.getAnnotation(Select.class);
    assertNotNull(annotation, method + " 必须仍然是手写 @Select");
    return String.join(" ", annotation.value());
  }

  private static String normalize(String sql) {
    return sql.replaceAll("\\s+", " ").trim().toUpperCase();
  }
}
