package io.openware.infrastructure.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 动作码字典：中文标签可组合推导，且已登记动作码清单不为空（前端筛选下拉的数据源）。 */
class AuditActionsTest {

  @Test
  void explicitLabelsWin() {
    assertEquals("结台结算", AuditActions.labelOf("order.settle"));
    assertEquals("组合收款", AuditActions.labelOf("payment.collect"));
    assertEquals("图片上传", AuditActions.labelOf("media.image.upload"));
    assertEquals("", AuditActions.labelOf(null));
    assertEquals("", AuditActions.labelOf(""));
  }

  /**
   * SaaS 后台审计补齐（2026-09）新上报的 7 个动作码必须有中文标签。
   *
   * <p>这些动作码此前「已登记但从不产生」（商品分类、目录项、清洁状态）或「产生但没登记」
   * （角色新建）——前者是审计缺口，后者会让前端筛选与列表里出现裸码。
   */
  @Test
  void saasAdminGapActionsHaveExplicitLabels() {
    assertEquals("商品分类新建", AuditActions.labelOf("product.category.create"));
    assertEquals("商品分类修改", AuditActions.labelOf("product.category.update"));
    assertEquals("商品分类删除", AuditActions.labelOf("product.category.delete"));
    assertEquals("点单目录项新建", AuditActions.labelOf("catalog.item.create"));
    assertEquals("点单目录项修改", AuditActions.labelOf("catalog.item.update"));
    assertEquals("点单目录项删除", AuditActions.labelOf("catalog.item.delete"));
    assertEquals("包厢清洁状态切换", AuditActions.labelOf("resource.cleaning.update"));
    assertEquals("角色新建", AuditActions.labelOf("iam.role.create"));
  }

  /** 保留策略的自留审计动作码也必须有标签（归档/预建都是合规相关动作，日志里不能出现裸码）。 */
  @Test
  void retentionActionsHaveExplicitLabels() {
    assertEquals("审计月份归档登记", AuditActions.labelOf("audit.retention.archive"));
    assertEquals("审计分区预建", AuditActions.labelOf("audit.retention.partition.add"));
  }

  @Test
  void unknownCodesStillGetComposableLabelsOrFallBackToCode() {
    assertEquals("预约-修改", AuditActions.labelOf("reservation.update"));
    assertEquals("员工新建", AuditActions.labelOf("staff.create"));
    assertEquals("unknown.thing", AuditActions.labelOf("unknown.thing"));
  }

  @Test
  void catalogExposesStableCodesAndLabels() {
    List<Map<String, String>> catalog = AuditActions.catalog();
    assertFalse(catalog.isEmpty());
    assertTrue(catalog.stream().anyMatch(item -> "order.settle".equals(item.get("code"))
        && "结台结算".equals(item.get("label"))));
    assertTrue(catalog.stream().allMatch(item -> item.get("code") != null && !item.get("code").isBlank()
        && item.get("label") != null && !item.get("label").isBlank()));
  }
}
