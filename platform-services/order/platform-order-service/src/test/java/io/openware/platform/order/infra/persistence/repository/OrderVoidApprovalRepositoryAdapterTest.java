package io.openware.platform.order.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.domain.order.model.OrderVoidApproval;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.infra.persistence.mapper.OrdOrderVoidApprovalMapper;
import io.openware.platform.order.infra.persistence.po.OrdOrderVoidApprovalPo;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 作废审批仓储适配器的条件写入（真实适配器 + mock mapper）。
 *
 * <p><b>为什么这个类必须存在</b>：{@code ord_order_void_approval} 的审批与执行是订单作废的闸门——
 * 审批通过后要触发订单作废与包厢释放。两条语句的条件谓词是**唯一的并发判定点**：
 * <ul>
 *   <li>{@code reviewWithVersion} 必须同时带 {@code version = ?} 与 {@code status = 'PENDING'}：
 *       少了 version，两个审批者都成功；少了状态，已批准/已执行的申请会被再审一次。</li>
 *   <li>{@code markExecuted} 必须同时带 {@code status = 'APPROVED'} 与 {@code version = ?}：
 *       <b>version 条件曾经缺失</b>（端口收了 {@code currentVersion} 却没用在 WHERE 里），
 *       于是两个并发执行都返回成功，各自触发一次订单作废与包厢释放，而两次调用方都被告知成功。
 *       本测试钉住这个修复。</li>
 * </ul>
 * 应用层测试只断言"调了哪个端口方法"，看不到 SQL；删掉任一谓词 {@code test-compile} 依然干净。
 *
 * <p>断言方式：捕获传给 mapper 的 Wrapper，用 MyBatis-Plus 自己生成的 SQL 求值
 * （{@code getTargetSql()} / {@code getSqlSet()}），不手写 SQL 比对。条件值按 Java 类型装箱
 * （{@code Integer} vs {@code Long}），数值比较一律走 {@code longValue()}。
 * {@code @BeforeAll} 里的 {@code TableInfoHelper.initTableInfo} 不可省：没有它 lambda 列名解析是
 * <b>空操作</b>，{@code getTargetSql()} 里没有列，断言会"莫名其妙地空"。
 */
class OrderVoidApprovalRepositoryAdapterTest {

  private static final long APPROVAL_ID = 91L;
  private static final long TENANT_ID = 100L;

  @BeforeAll
  static void registerTableInfo() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        OrdOrderVoidApprovalPo.class);
  }

  private final OrdOrderVoidApprovalMapper mapper = mock(OrdOrderVoidApprovalMapper.class);
  private final OrderVoidApprovalRepositoryAdapter adapter =
      new OrderVoidApprovalRepositoryAdapter(mapper);

  // ------------------------------------------------------------ 审批（reviewWithVersion）

  /**
   * 审批的条件必须同时含 id / tenant / version / status=PENDING，且版本自增写在 SQL 里。
   *
   * <p>{@code tenant} 不能省：id 全局唯一但审批是租户内资源，漏掉它等于允许跨租户审批。
   */
  @Test
  void reviewIsConditionalOnVersionAndPendingStatus() {
    when(mapper.update(any(), any())).thenReturn(1);
    when(mapper.selectById(APPROVAL_ID)).thenReturn(po(APPROVAL_ID));

    adapter.reviewWithVersion(APPROVAL_ID, TENANT_ID, 7, OrderVoidApproval.STATUS_APPROVED, 5L,
        "同意", LocalDateTime.now());

    LambdaUpdateWrapper<OrdOrderVoidApprovalPo> wrapper = capturedUpdate();
    String where = evaluatedSql(wrapper);
    assertTrue(where.contains("ID = ?"), "必须按主键定位: " + where);
    assertTrue(where.contains("TENANT_ID = ?"), "必须限定租户，否则可跨租户审批: " + where);
    assertTrue(where.contains("VERSION = ?"), "必须有 version 条件，否则两人都能审批: " + where);
    assertTrue(where.contains("STATUS = ?"), "必须限定 PENDING，否则已批准的可被再审: " + where);

    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(containsNumber(params, APPROVAL_ID), "按审批 id 定位: " + params.values());
    assertTrue(containsNumber(params, TENANT_ID), "按租户定位: " + params.values());
    assertTrue(containsNumber(params, 7), "期望版本必须原样进入条件: " + params.values());
    assertTrue(containsParam(params, OrderVoidApproval.STATUS_PENDING),
        "状态条件必须绑定 PENDING: " + params.values());
    assertTrue(containsParam(params, OrderVoidApproval.STATUS_APPROVED),
        "写入的目标状态必须是 APPROVED: " + params.values());
    // 版本自增必须写在 SQL 里。用精确形态判定：普通 SET 列会是 `version=#{...}`，
    // 而 setSql 自增在求值后的 SQL 里呈现为 `version=version+1`。
    // 不能只搜 "version"——order_version 列也含这个词，会误报。
    String set = String.valueOf(wrapper.getSqlSet()).replace(" ", "").toLowerCase();
    assertFalse(set.contains("version=#{"),
        "version 不得作为普通 SET 列写入（应走 setSql 自增）: " + wrapper.getSqlSet());
    assertTrue(set.contains("version=version+1"),
        "版本自增必须写在 SQL 里，避免把读出来的旧版本写回: " + wrapper.getSqlSet());
    assertTrue(wrapper.getSqlSet() != null && !wrapper.getSqlSet().isBlank(), "必须有 SET 片段");
  }

  /** 受影响 0 行 = 版本或状态不再匹配：必须回 {@code Optional.empty()}，不得当成成功。 */
  @Test
  void reviewReturnsEmptyWhenNoRowAffected() {
    when(mapper.update(any(), any())).thenReturn(0);

    assertTrue(adapter.reviewWithVersion(APPROVAL_ID, TENANT_ID, 7, OrderVoidApproval.STATUS_APPROVED,
        5L, "同意", LocalDateTime.now()).isEmpty());
  }

  // ------------------------------------------------------------------ 执行（markExecuted）

  /**
   * <b>本类最要紧的一条</b>：执行必须同时带 {@code status = 'APPROVED'} 与 {@code version = ?}。
   *
   * <p>version 条件曾经缺失，{@code currentVersion} 收了却没用——两个并发执行都会成功，
   * 各自触发一次订单作废与包厢释放。删掉 {@code .eq(version, ...)} 会让这条断言失败。
   */
  @Test
  void markExecutedIsConditionalOnApprovedStatusAndVersion() {
    when(mapper.update(any(), any())).thenReturn(1);

    assertTrue(adapter.markExecuted(APPROVAL_ID, 3, LocalDateTime.now()));

    LambdaUpdateWrapper<OrdOrderVoidApprovalPo> wrapper = capturedUpdate();
    String where = evaluatedSql(wrapper);
    assertTrue(where.contains("ID = ?"), "必须按主键定位: " + where);
    assertTrue(where.contains("STATUS = ?"), "必须限定 APPROVED，否则未批准的也能执行: " + where);
    assertTrue(where.contains("VERSION = ?"),
        "必须有 version 条件，否则两个并发执行都会成功并各触发一次作废: " + where);

    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(containsNumber(params, APPROVAL_ID), params.values().toString());
    assertTrue(containsNumber(params, 3), "currentVersion 必须参与判定，不能只是签名装饰: " + params.values());
    assertTrue(containsParam(params, OrderVoidApproval.STATUS_APPROVED), params.values().toString());
    assertTrue(containsParam(params, OrderVoidApproval.STATUS_EXECUTED), params.values().toString());
  }

  /** 受影响 0 行必须返回 false：调用方据此报冲突，不得静默成功。 */
  @Test
  void markExecutedReturnsFalseWhenNoRowAffected() {
    when(mapper.update(any(), any())).thenReturn(0);

    assertFalse(adapter.markExecuted(APPROVAL_ID, 3, LocalDateTime.now()));
  }

  // ------------------------------------------------------------------- 语句级兜底

  /**
   * 读 {@code OrdOrderVoidApprovalMapper} 上的 {@code @Update} 文本。
   *
   * <p>当前实现走 Wrapper（不是手写 @Update），因此这条断言的作用是：**一旦有人把语句改成手写 SQL
   * 并漏掉谓词，下面的反射断言会失败**，而不是让谓词悄悄消失。
   */
  @Test
  void mapperDeclaresNoUnconditionalUpdate() throws Exception {
    for (Method method : OrdOrderVoidApprovalMapper.class.getDeclaredMethods()) {
      Update update = method.getAnnotation(Update.class);
      if (update == null) {
        continue;
      }
      String sql = String.join(" ", update.value()).toUpperCase();
      if (sql.contains("UPDATE")) {
        assertTrue(sql.contains("WHERE"),
            method.getName() + " 的手写 UPDATE 必须带 WHERE，否则是无条件覆盖: " + sql);
      }
    }
  }

  // -------------------------------------------------------------------- helpers

  private static String evaluatedSql(AbstractWrapper<?, ?, ?> wrapper) {
    return wrapper.getTargetSql().replaceAll("\\s+", " ").trim().toUpperCase();
  }

  private static Map<String, Object> evaluatedParams(AbstractWrapper<?, ?, ?> wrapper) {
    evaluatedSql(wrapper);
    return wrapper.getParamNameValuePairs();
  }

  private static boolean containsNumber(Map<String, Object> params, long expected) {
    return params.values().stream()
        .anyMatch(value -> value instanceof Number number && number.longValue() == expected);
  }

  private static boolean containsParam(Map<String, Object> params, Object expected) {
    for (Object value : params.values()) {
      if (value instanceof java.util.Collection<?> collection) {
        if (collection.contains(expected)) {
          return true;
        }
      } else if (expected.equals(value)) {
        return true;
      }
    }
    return false;
  }

  private static OrdOrderVoidApprovalPo po(Long id) {
    OrdOrderVoidApprovalPo po = new OrdOrderVoidApprovalPo();
    po.setId(id);
    po.setTenantId(TENANT_ID);
    po.setStatus(OrderVoidApproval.STATUS_APPROVED);
    po.setVersion(8);
    po.setCreatedAt(LocalDateTime.now());
    po.setUpdatedAt(LocalDateTime.now());
    return po;
  }

  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<OrdOrderVoidApprovalPo> capturedUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<OrdOrderVoidApprovalPo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
    LambdaUpdateWrapper<OrdOrderVoidApprovalPo> wrapper = captor.getValue();
    assertNotNull(wrapper);
    return wrapper;
  }
}
