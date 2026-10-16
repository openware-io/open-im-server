package io.openware.platform.order.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.domain.reservation.model.Reservation;
import io.openware.platform.order.domain.reservation.model.ReservationStatus;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository.Persisted;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository.WriteAudit;
import io.openware.platform.order.infra.persistence.mapper.ReservationMapper;
import io.openware.platform.order.infra.persistence.po.ReservationPo;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * 预约仓储适配器的 SQL 形状与列保真（真实适配器 + mock mapper）。
 *
 * <p><b>为什么这个类必须存在</b>：预约的三条写路径都是「读出来判定 → 带 {@code version} 条件写回」，
 * 而条件写回的确切列集合**只能在这一层验证**。应用层测试现在只能断言「哪个端口方法被调用」，
 * 看不到 SQL；把它留在应用层测试里正是改造前那几条 {@code getSqlSet()} 断言的位置，而它们已经随
 * 端口化消失。本类把两类风险钉住：
 * <ol>
 *   <li><b>列保真</b>：{@code ReservationPo} 上有三个 {@code @TableField(exist = false)} 字段
 *       （{@code roomTypeCode}/{@code roomTypeName}/{@code resourceName}）。对它们生成列引用会在
 *       <b>运行时</b>抛 {@code can not find lambda cache for this property [...]}，{@code test-compile}
 *       完全干净——钱包切片的 CAS 适配器已经栽过一次；</li>
 *   <li><b>列越界</b>：状态流转若顺带写 {@code resource_id}/{@code start_at}/{@code end_at}，
 *       并发的「分配包厢/改期」结果就会被覆盖回旧值；分配包厢若写 {@code status}，
 *       锁房就变成了「客人已到店」。</li>
 * </ol>
 * 断言方式：捕获传给 mapper 的 {@code AbstractWrapper}，用 MyBatis-Plus 自己生成的 SQL 求值
 * （{@code getTargetSql()} / {@code getSqlSet()}），不手写 SQL 比对；条件值按 Java 类型装箱
 * （{@code Integer} vs {@code Long}），因此数值比较一律走 {@code longValue()}。
 * {@code @BeforeAll} 里的 {@code TableInfoHelper.initTableInfo} 不可省：没有它，lambda 的列名解析
 * 是**空操作**，{@code getSqlSet()} 里没有列，断言会「莫名其妙地空」而不是报错。
 */
class ReservationRepositoryAdapterTest {

  private static final long RESERVATION_ID = 9L;
  private static final long TENANT_ID = 7L;
  private static final long STORE_ID = 1001L;
  private static final long ROOM_TYPE_ID = 55L;
  private static final long RESOURCE_ID = 3001L;
  private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 9, 18, 20, 0);
  private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 9, 18, 22, 0);
  private static final WriteAudit AUDIT = new WriteAudit(42L, LocalDateTime.of(2026, 9, 18, 19, 30));

  @BeforeAll
  static void registerTableInfo() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
    assistant.setCurrentNamespace(ReservationRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, ReservationPo.class);
  }

  private final ReservationMapper mapper = mock(ReservationMapper.class);
  private final ReservationRepositoryAdapter adapter = new ReservationRepositoryAdapter(mapper);

  // ------------------------------------------------------- 非列字段（已发生过一次的缺陷）

  /**
   * {@code roomTypeCode}/{@code roomTypeName}/{@code resourceName} 不是列。
   *
   * <p>非列字段集变了就说明写入路径的列集合必须重新复核——这是本类最要紧的一条。
   */
  @Test
  void tableInfoRegistersTheThreeDisplayFieldsAsNonColumns() throws Exception {
    TableInfo info = TableInfoHelper.getTableInfo(ReservationPo.class);
    assertNotNull(info, "ReservationPo 的 TableInfo 必须已注册（SQL 形状断言的前提）");
    Set<String> columnProperties = info.getFieldList().stream()
        .map(TableFieldInfo::getProperty).collect(Collectors.toSet());
    Set<String> nonColumnProperties = new LinkedHashSet<>();
    for (Field field : ReservationPo.class.getDeclaredFields()) {
      TableField annotation = field.getAnnotation(TableField.class);
      if (annotation != null && !annotation.exist()) {
        nonColumnProperties.add(field.getName());
      }
    }

    assertEquals(Set.of("roomTypeCode", "roomTypeName", "resourceName"), nonColumnProperties,
        "非列字段集变了：写入路径的列集合必须同步复核");
    for (String property : nonColumnProperties) {
      assertFalse(columnProperties.contains(property),
          property + " 标了 @TableField(exist = false)，绝不能出现在列集合里");
    }
    assertTrue(columnProperties.contains("reservationNo"), "reservation_no 是列");
    assertTrue(columnProperties.contains("arrivedAt"), "arrived_at 是列");
    assertTrue(columnProperties.contains("version"), "version 是列");
  }

  /** 领域模型 → 持久化对象时一个展示字段都不设（设了就可能进 INSERT/SET）。 */
  @Test
  void toPoNeverSetsTheDisplayOnlyFields() {
    ReservationPo po = ReservationRepositoryAdapter.toPo(reservation(ReservationStatus.CONFIRMED, null));

    assertNull(po.getRoomTypeCode(), "roomTypeCode 不是列，适配器不得写入");
    assertNull(po.getRoomTypeName(), "roomTypeName 不是列，适配器不得写入");
    assertNull(po.getResourceName(), "resourceName 不是列，适配器不得写入");
  }

  // ------------------------------------------------------------------ 新增

  /** 新增：业务列 + 记账列来自领域聚合与 WriteAudit；version=0、状态用枚举名落库。 */
  @Test
  void insertWritesBusinessColumnsAndAuditFromTheWriteStamp() {
    when(mapper.insert(any(ReservationPo.class))).thenReturn(1);

    Persisted<Reservation> inserted = adapter.insert(reservation(ReservationStatus.PENDING, null), AUDIT);

    ArgumentCaptor<ReservationPo> captor = ArgumentCaptor.forClass(ReservationPo.class);
    verify(mapper).insert(captor.capture());
    ReservationPo written = captor.getValue();
    assertEquals(RESERVATION_ID, written.getId());
    assertEquals(TENANT_ID, written.getTenantId());
    assertEquals(STORE_ID, written.getStoreId());
    assertEquals("R2026091800000000001", written.getReservationNo());
    assertEquals(ROOM_TYPE_ID, written.getRoomTypeId());
    assertEquals("PENDING", written.getStatus(), "状态用枚举名落库（DDL 闭集）");
    assertEquals(0, written.getVersion());
    assertEquals(4, written.getPartySize());
    assertEquals(WINDOW_START, written.getStartAt());
    assertEquals(WINDOW_END, written.getEndAt());
    assertEquals(42L, written.getCreatedBy(), "created_by 取写入记账值（操作人），不是租户上下文常量");
    assertEquals(AUDIT.at(), written.getCreatedAt());
    assertEquals(42L, written.getUpdatedBy());
    assertEquals(AUDIT.at(), written.getUpdatedAt());
    assertNull(written.getRoomTypeCode(), "新增不得写展示字段");
    // 回填的聚合与记账快照来自写出去的那一行。
    assertEquals(RESERVATION_ID, inserted.aggregate().id());
    assertEquals(42L, inserted.audit().createdBy());
    assertEquals(AUDIT.at(), inserted.audit().updatedAt());
  }

  // ------------------------------------------------------------------ 三条 CAS

  /**
   * 状态流转必须是**单条带 version 条件**的 UPDATE，且版本自增写在 SQL 里。
   *
   * <p>版本自增若由 Java 读出后 +1（{@code .set(version, old + 1)}），并发窗口内会写回过期版本号。
   */
  @Test
  void updateStatusWithVersionIsAConditionalUpdateWithSqlSideIncrement() {
    when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(1);

    assertTrue(adapter.updateStatusWithVersion(reservation(ReservationStatus.CONFIRMED, null), 3, AUDIT));

    LambdaUpdateWrapper<ReservationPo> wrapper = capturedUpdate();
    String sql = evaluatedSql(wrapper);
    assertTrue(sql.contains("ID = ?"), "CAS 必须按 id 定位: " + sql);
    assertTrue(sql.contains("VERSION = ?"), "CAS 必须带 version 条件: " + sql);
    assertTrue(wrapper.getSqlSet().replace(" ", "").toUpperCase().contains("VERSION=VERSION+1"),
        "版本自增必须写在 SQL 里: " + wrapper.getSqlSet());
  }

  /** 状态流转只写状态/订单回填/到店时间与记账列；绝不碰包厢与时段列。 */
  @Test
  void updateStatusWritesStatusAndNeverAssignmentOrSchedule() {
    when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(1);
    Reservation arrived = reservation(ReservationStatus.ARRIVED, RESOURCE_ID, 88L,
        LocalDateTime.of(2026, 9, 18, 20, 5));

    adapter.updateStatusWithVersion(arrived, 3, AUDIT);

    String set = capturedUpdate().getSqlSet();
    assertTrue(set.contains("status"), "必须写 status: " + set);
    assertTrue(set.contains("order_id"), "order_id 非空时必须回填: " + set);
    assertTrue(set.contains("arrived_at"), "arrived_at 非空时必须写（到店事实）: " + set);
    assertTrue(set.contains("updated_by") && set.contains("updated_at"), "必须刷新记账列: " + set);
    assertFalse(set.contains("resource_id"), "状态流转不得改写包厢分配: " + set);
    assertFalse(set.contains("start_at"), "状态流转不得改写时段: " + set);
    assertFalse(set.contains("end_at"), "状态流转不得改写时段: " + set);
    assertFalse(set.contains("reservation_no"), "单号不可变: " + set);
    assertFalse(set.contains("tenant_id"), "租户不可变: " + set);
    assertFalse(set.contains("created_at"), "created_at 不可变: " + set);
    assertFalse(set.contains("room_type_code"), "展示字段不是列: " + set);
  }

  /**
   * 状态流转的「保持原值」语义：{@code orderId}/{@code arrivedAt} 为空时**不写列**
   * （写 null 会把已开台预约的 order_id 抹掉，那正是「预约取消了、订单还在计时」的悬挂来源）。
   */
  @Test
  void updateStatusLeavesOrderAndArrivalUntouchedWhenAbsent() {
    when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(1);

    adapter.updateStatusWithVersion(reservation(ReservationStatus.PENDING, null), 0, AUDIT);

    String set = capturedUpdate().getSqlSet();
    assertTrue(set.contains("status"), set);
    assertFalse(set.contains("order_id"), "未开台时不得写 order_id: " + set);
    assertFalse(set.contains("arrived_at"), "未到店时不得写 arrived_at: " + set);
  }

  /** 分配包厢：**只写 resource_id**，状态不在更新集合里（锁房 ≠ 客人到店）。 */
  @Test
  void updateAssignmentWritesResourceIdAndNeverStatus() {
    when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(1);

    assertTrue(adapter.updateAssignmentWithVersion(reservation(ReservationStatus.CONFIRMED, RESOURCE_ID), 0, AUDIT));

    LambdaUpdateWrapper<ReservationPo> wrapper = capturedUpdate();
    String set = wrapper.getSqlSet();
    assertTrue(set.contains("resource_id"), "必须写 resource_id: " + set);
    assertTrue(set.contains("updated_at"), "必须刷新 updated_at: " + set);
    assertFalse(set.contains("status"), "分配包厢不得改状态（到店事实只由状态流转写）: " + set);
    assertFalse(set.contains("start_at") || set.contains("end_at"), "分配包厢不得改时段: " + set);
    assertTrue(wrapper.getSqlSet().replace(" ", "").toUpperCase().contains("VERSION=VERSION+1"), set);
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(containsNumber(params, RESERVATION_ID), "按 id 定位: " + params.values());
    assertTrue(containsNumber(params, 0), "期望版本原样进入条件: " + params.values());
    assertTrue(containsNumber(params, RESOURCE_ID), "写入目标包厢: " + params.values());
  }

  /** 改期：只写 start_at/end_at（+ 记账列），不碰状态与包厢。 */
  @Test
  void updateScheduleWritesOnlyTheWindow() {
    when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(1);
    Reservation moved = reservation(ReservationStatus.CONFIRMED, RESOURCE_ID).rescheduledTo(
        LocalDateTime.of(2026, 9, 19, 21, 0), LocalDateTime.of(2026, 9, 19, 23, 0));

    assertTrue(adapter.updateScheduleWithVersion(moved, 1, AUDIT));

    String set = capturedUpdate().getSqlSet();
    assertTrue(set.contains("start_at") && set.contains("end_at"), "必须写新时段: " + set);
    assertFalse(set.contains("status"), "改期不得改状态: " + set);
    assertFalse(set.contains("resource_id"), "改期不得改包厢分配: " + set);
  }

  /** 受影响 0 行 = 期间已被他人写入：三条 CAS 都必须原样返回 false，适配器不得吞掉这个信号。 */
  @Test
  void conditionalUpdatesReturnFalseWhenZeroRowsAffected() {
    when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(0);
    Reservation po = reservation(ReservationStatus.CONFIRMED, RESOURCE_ID);

    assertFalse(adapter.updateStatusWithVersion(po, 0, AUDIT));
    assertFalse(adapter.updateAssignmentWithVersion(po, 0, AUDIT));
    assertFalse(adapter.updateScheduleWithVersion(po, 0, AUDIT));
  }

  // ------------------------------------------------------------------ 超订计数与时段冲突

  /**
   * 超订计数的状态口径（回归）：只有 PENDING/CONFIRMED/ARRIVED 占名额，CONVERTED 绝不算
   * ——否则开台后包厢即便空闲也一直显示已满，C 端约不出房。
   *
   * <p>应用层测试已经无法断言这条（状态集合不再由它拼出来），所以它是本类存在的第二个理由。
   */
  @Test
  void countSlotHoldingCountsOnlySlotHoldingStatuses() {
    when(mapper.selectCount(any())).thenReturn(2L);

    assertEquals(2L, adapter.countSlotHolding(TENANT_ID, STORE_ID, ROOM_TYPE_ID, WINDOW_START, WINDOW_END));

    LambdaQueryWrapper<ReservationPo> wrapper = capturedCount();
    String sql = evaluatedSql(wrapper);
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(sql.contains("STATUS IN"), "状态必须是闭集条件: " + sql);
    assertTrue(sql.contains("START_AT < ?") && sql.contains("END_AT > ?"),
        "左闭右开的重叠判定: " + sql);
    assertTrue(containsParam(params, "PENDING"), "待确认应占名额: " + params.values());
    assertTrue(containsParam(params, "CONFIRMED"), "已确认应占名额: " + params.values());
    assertTrue(containsParam(params, "ARRIVED"), "已到店应占名额: " + params.values());
    assertFalse(containsParam(params, "CONVERTED"), "已开台不应再占名额: " + params.values());
    assertFalse(containsParam(params, "CANCELLED"), "已取消不占名额: " + params.values());
    assertFalse(containsParam(params, "NO_SHOW"), "未到店不占名额: " + params.values());
    assertTrue(containsNumber(params, TENANT_ID), "按租户计数: " + params.values());
    assertTrue(containsNumber(params, STORE_ID), "按门店计数: " + params.values());
    assertTrue(containsNumber(params, ROOM_TYPE_ID), "按房型计数: " + params.values());
  }

  /** 包厢时段冲突：排除自己、只取仍占时段的状态、按 start_at 升序（最早的一条用于提示）。 */
  @Test
  void findRoomOverlapExcludesSelfAndOrdersByStart() {
    when(mapper.selectList(any())).thenReturn(List.of(po(77L)));

    Optional<Reservation> conflict = adapter.findRoomOverlap(STORE_ID, RESOURCE_ID, RESERVATION_ID,
        WINDOW_START, WINDOW_END);

    assertTrue(conflict.isPresent());
    LambdaQueryWrapper<ReservationPo> wrapper = capturedSelect();
    String sql = evaluatedSql(wrapper);
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(containsNumber(params, RESOURCE_ID), "按包厢定位: " + params.values());
    assertTrue(sql.contains("ID <> ?"), "必须排除自己: " + sql);
    assertTrue(sql.contains("STATUS IN"), sql);
    assertTrue(sql.contains("ORDER BY") && sql.contains("START_AT ASC"), "按开始时间升序: " + sql);
    assertFalse(containsParam(params, "CONVERTED"), "已开台的时段归属由资源域承载: " + params.values());
  }

  /** 候选列表口径：一次取回本时段全部冲突，只取已分配包厢的行。 */
  @Test
  void findRoomOverlapsFiltersAssignedRooms() {
    when(mapper.selectList(any())).thenReturn(List.of(po(77L)));

    Map<Long, Reservation> conflicts = adapter.findRoomOverlaps(STORE_ID, RESERVATION_ID, WINDOW_START, WINDOW_END);

    assertEquals(1, conflicts.size());
    assertTrue(conflicts.containsKey(RESOURCE_ID), "键是包厢 id: " + conflicts.keySet());
    String sql = evaluatedSql(capturedSelect());
    assertTrue(sql.contains("RESOURCE_ID IS NOT NULL"), "只取已分配包厢的行: " + sql);
  }

  // ------------------------------------------------------------------ 读取

  /** 幂等命中：按租户 + 幂等键定位，且幂等键为空时不查库（避免空串匹配到别人的行）。 */
  @Test
  void findByIdempotencyKeyFiltersByTenantAndShortCircuitsOnBlank() {
    when(mapper.selectList(any())).thenReturn(List.of(po(RESERVATION_ID)));

    assertTrue(adapter.findByIdempotencyKey(TENANT_ID, "idem-1").isPresent());
    LambdaQueryWrapper<ReservationPo> wrapper = capturedSelect();
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(containsNumber(params, TENANT_ID), "幂等键只在租户内唯一，必须显式过滤租户: " + params.values());
    assertTrue(containsParam(params, "idem-1"), String.valueOf(params.values()));
    assertTrue(evaluatedSql(wrapper).contains("LIMIT 1"), "只取一行");

    assertTrue(adapter.findByIdempotencyKey(TENANT_ID, "  ").isEmpty());
  }

  /** 空 id 不查库（零额视图/未落库聚合的 id 就是 null，不能把它当成一次查询）。 */
  @Test
  void findByIdShortCircuitsOnNullId() {
    assertTrue(adapter.findById(null).isEmpty());
  }

  /** 列表读路径：时间条件落在 start_at 列上（不用函数包裹），倒序；空边界不拼条件。 */
  @Test
  void findByStartAtRangeAppliesClosedBoundsAndDescOrder() {
    when(mapper.selectList(any())).thenReturn(List.of(po(RESERVATION_ID)));

    List<Persisted<Reservation>> rows = adapter.findByStartAtRange(WINDOW_START, WINDOW_END);

    assertEquals(1, rows.size());
    String sql = evaluatedSql(capturedSelect());
    assertTrue(sql.contains("START_AT >= ?") && sql.contains("START_AT <= ?"), sql);
    assertFalse(sql.contains("DATE("), "时间列不得被函数包裹，否则索引失效: " + sql);
    assertTrue(sql.contains("ORDER BY START_AT DESC"), "排序口径保持倒序: " + sql);

    adapter.findByStartAtRange(null, null);
    String unbounded = evaluatedSql(secondSelect());
    assertFalse(unbounded.contains("START_AT >= ?") || unbounded.contains("START_AT <= ?"),
        "不筛时不得拼出时间条件: " + unbounded);
  }

  /** 未知状态是代码/DDL 不一致：解析必须抛出，不得静默退化成一个「可取消」的状态。 */
  @Test
  void toDomainRejectsUnknownStatus() {
    ReservationPo po = po(RESERVATION_ID);
    po.setStatus("SOMETHING_ELSE");

    assertThrows(IllegalStateException.class, () -> ReservationRepositoryAdapter.toDomain(po));
  }

  // ---------------------------------------------------------------- helpers

  private static Reservation reservation(ReservationStatus status, Long resourceId) {
    return reservation(status, resourceId, null, null);
  }

  private static Reservation reservation(ReservationStatus status, Long resourceId, Long orderId,
                                         LocalDateTime arrivedAt) {
    return new Reservation(RESERVATION_ID, TENANT_ID, STORE_ID, "R2026091800000000001", null, 99L, "KTV",
        resourceId, ROOM_TYPE_ID, WINDOW_START, WINDOW_END, 4, "张三 13800138000", status, orderId, arrivedAt, 0);
  }

  private static ReservationPo po(Long id) {
    ReservationPo po = new ReservationPo();
    po.setId(id);
    po.setTenantId(TENANT_ID);
    po.setStoreId(STORE_ID);
    po.setReservationNo("R2026091800000000001");
    po.setCustomerId(99L);
    po.setBusinessType("KTV");
    po.setResourceId(RESOURCE_ID);
    po.setRoomTypeId(ROOM_TYPE_ID);
    po.setStartAt(WINDOW_START);
    po.setEndAt(WINDOW_END);
    po.setPartySize(4);
    po.setStatus("CONFIRMED");
    po.setVersion(3);
    po.setCreatedBy(9L);
    po.setCreatedAt(LocalDateTime.of(2026, 9, 18, 9, 0));
    po.setUpdatedBy(9L);
    po.setUpdatedAt(LocalDateTime.of(2026, 9, 18, 9, 30));
    return po;
  }

  private static String evaluatedSql(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
    return wrapper.getTargetSql().replaceAll("\\s+", " ").trim().toUpperCase();
  }

  /** 求值后的参数表（先触发 SQL 求值，再读参数——{@code eq}/{@code set} 的值是惰性进参数表的）。 */
  private static Map<String, Object> evaluatedParams(
      com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
    evaluatedSql(wrapper);
    return wrapper.getParamNameValuePairs();
  }

  /** 条件值按 Java 类型装箱（Integer vs Long），必须按数值比较。 */
  private static boolean containsNumber(Map<String, Object> params, long expected) {
    return params.values().stream()
        .anyMatch(value -> value instanceof Number number && number.longValue() == expected);
  }

  /** {@code eq} 的值是标量、{@code in} 的值是集合，两种都要能命中。 */
  private static boolean containsParam(Map<String, Object> params, Object expected) {
    for (Object value : params.values()) {
      if (value instanceof Collection<?> collection) {
        if (collection.contains(expected)) {
          return true;
        }
      } else if (expected.equals(value)) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<ReservationPo> capturedUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<ReservationPo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(mapper).update(ArgumentMatchers.isNull(), captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<ReservationPo> capturedSelect() {
    ArgumentCaptor<LambdaQueryWrapper<ReservationPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue();
  }

  /** 计数走 {@code selectCount}（不是 {@code selectList}）：条件形状必须从计数那条语句上读。 */
  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<ReservationPo> capturedCount() {
    ArgumentCaptor<LambdaQueryWrapper<ReservationPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectCount(captor.capture());
    return captor.getValue();
  }

  /** 第二次（且最后一次）查询的条件：用于「同一个用例里查两次」的场景。 */
  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<ReservationPo> secondSelect() {
    ArgumentCaptor<LambdaQueryWrapper<ReservationPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper, org.mockito.Mockito.times(2)).selectList(captor.capture());
    return captor.getAllValues().get(1);
  }
}
