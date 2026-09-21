package com.gvchat.infrastructure.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计动作码字典：稳定码（{@code <模块>.<对象>.<动作>}）与中文标签的唯一出处。
 *
 * <p>设计口径（SAAS_PLATFORM_02 §9.4）：
 * <ul>
 *   <li>动作码是**稳定契约**，不随页面/控制器改名而变化；前端筛选、报表、告警都按码对接。</li>
 *   <li>中文标签可演进：显式登记优先，未登记时按「模块标签 + 动作标签」组合推导，
 *       保证任何新动作码都能给出可读标签，不会出现空标签或英文裸码。</li>
 *   <li>未登记的动作码**仍然允许写入**（标签退化为码本身），避免上报方与服务端发布顺序造成审计丢失。</li>
 * </ul>
 */
public final class AuditActions {

  private static final String SEPARATOR = ".";

  /** 显式登记的动作码 → 中文标签（语义精确、不可机械组合的优先走这里）。 */
  private static final Map<String, String> EXPLICIT_LABELS = new LinkedHashMap<>();
  /** 模块/对象码 → 中文名。 */
  private static final Map<String, String> MODULE_LABELS = new LinkedHashMap<>();
  /** 动作码 → 中文名。 */
  private static final Map<String, String> VERB_LABELS = new LinkedHashMap<>();

  static {
    MODULE_LABELS.put("order", "订单");
    MODULE_LABELS.put("ktv", "KTV");
    MODULE_LABELS.put("payment", "收款");
    MODULE_LABELS.put("refund", "退款");
    MODULE_LABELS.put("cashier", "收银");
    MODULE_LABELS.put("inventory", "库存");
    MODULE_LABELS.put("product", "商品");
    MODULE_LABELS.put("catalog", "目录");
    MODULE_LABELS.put("resource", "资源");
    MODULE_LABELS.put("roomtype", "房型");
    MODULE_LABELS.put("reservation", "预约");
    MODULE_LABELS.put("member", "会员");
    MODULE_LABELS.put("wallet", "会员储值");
    MODULE_LABELS.put("points", "会员积分");
    MODULE_LABELS.put("customer", "会员");
    MODULE_LABELS.put("tenant", "租户");
    MODULE_LABELS.put("store", "门店");
    MODULE_LABELS.put("organization", "组织");
    MODULE_LABELS.put("iam", "权限");
    MODULE_LABELS.put("role", "角色");
    MODULE_LABELS.put("staff", "员工");
    MODULE_LABELS.put("user", "账号");
    MODULE_LABELS.put("admin", "后台");
    MODULE_LABELS.put("audit", "审计");
    MODULE_LABELS.put("media", "图片");
    MODULE_LABELS.put("report", "报表");
    MODULE_LABELS.put("channel", "渠道");
    MODULE_LABELS.put("payment-channel", "支付渠道");
    MODULE_LABELS.put("payment-method", "支付方式");
    MODULE_LABELS.put("pricing-plan", "定价方案");
    MODULE_LABELS.put("daily-closing", "日结");
    MODULE_LABELS.put("reconciliation", "对账");
    MODULE_LABELS.put("wallet-adjust", "储值调整");
    MODULE_LABELS.put("auth", "登录");
    MODULE_LABELS.put("context", "运营上下文");
    MODULE_LABELS.put("menu", "菜单");
    MODULE_LABELS.put("backend", "后台");
    MODULE_LABELS.put("feature", "功能开关");
    MODULE_LABELS.put("notification", "通知");
    MODULE_LABELS.put("marketing", "营销");
    MODULE_LABELS.put("sms", "短信");
    MODULE_LABELS.put("mail", "邮件");

    VERB_LABELS.put("create", "新建");
    VERB_LABELS.put("update", "修改");
    VERB_LABELS.put("delete", "删除");
    VERB_LABELS.put("remove", "移除");
    VERB_LABELS.put("assign", "分配");
    VERB_LABELS.put("revoke", "撤销");
    VERB_LABELS.put("grant", "授权");
    VERB_LABELS.put("toggle", "启停");
    VERB_LABELS.put("submit", "提交");
    VERB_LABELS.put("approve", "审批通过");
    VERB_LABELS.put("reject", "审批驳回");
    VERB_LABELS.put("cancel", "取消");
    VERB_LABELS.put("void", "作废");
    VERB_LABELS.put("adjust", "调整");
    VERB_LABELS.put("confirm", "确认");
    VERB_LABELS.put("upload", "上传");
    VERB_LABELS.put("download", "下载");
    VERB_LABELS.put("export", "导出");
    VERB_LABELS.put("import", "导入");
    VERB_LABELS.put("publish", "上架/发布");
    VERB_LABELS.put("unpublish", "下架");
    VERB_LABELS.put("open", "开台/开启");
    VERB_LABELS.put("close", "结台/关闭");
    VERB_LABELS.put("settle", "结算");
    VERB_LABELS.put("collect", "收款");
    VERB_LABELS.put("request", "申请");
    VERB_LABELS.put("select", "切换");
    VERB_LABELS.put("login", "登录");
    VERB_LABELS.put("logout", "登出");
    VERB_LABELS.put("change", "修改");
    VERB_LABELS.put("reset", "重置");
    VERB_LABELS.put("bind", "绑定");
    VERB_LABELS.put("unbind", "解绑");
    VERB_LABELS.put("operate", "操作");
    VERB_LABELS.put("operation", "操作");
    VERB_LABELS.put("recharge", "充值");
    VERB_LABELS.put("view", "查看");
    VERB_LABELS.put("hold", "挂单");
    VERB_LABELS.put("transfer", "转台");

    register("order.ktv_session.open", "开台");
    register("order.ktv_session.close", "结台");
    register("order.ktv_session.pause", "会话暂停");
    register("order.ktv_session.resume", "会话恢复");
    register("order.ktv_session.cancel", "会话取消");
    register("order.ktv_session.correct_pause", "暂停时长修正");
    register("order.ktv_server_session.order", "点服务人员");
    register("order.ktv_server_session.end", "服务人员结束服务");
    register("order.ktv_server_session.cancel", "服务人员点单取消");
    register("order.settle", "结台结算");
    register("order.void", "订单作废");
    register("order.cancel", "取消订单");
    register("order.hold", "订单挂单");
    register("order.unhold", "订单解挂");
    register("order.transfer", "订单转台");
    register("order.item.add", "订单加项");
    register("order.item.confirm", "订单加项确认");
    register("order.item.reject", "订单加项驳回");
    register("order.item.cancel", "订单加项撤销");
    register("order.recovery.confirm", "库存回补确认");
    register("inventory.adjust", "库存调整");
    register("inventory.receipt.create", "物料入库");
    register("inventory.material.create", "物料新建");
    register("inventory.material.update", "物料修改");
    register("payment.collect", "组合收款");
    register("payment.refund.request", "退款申请");
    register("payment.refund.approve", "退款审批通过");
    register("payment.refund.reject", "退款审批驳回");
    register("payment.refund.offline", "线下退款登记");
    register("cashier.shift.open", "开班");
    register("cashier.shift.close", "交班结账");
    register("cashier.daily_closing.close", "营业日结");
    register("payment-method.grant", "支付方式授权");
    register("payment-method.user_enabled", "支付方式用户开关");
    register("payment-channel.toggle", "支付渠道开通/关闭");
    register("product.create", "商品新建");
    register("product.update", "商品修改");
    register("product.publish", "商品上架");
    register("product.unpublish", "商品下架");
    register("product.category.create", "商品分类新建");
    register("product.category.update", "商品分类修改");
    register("product.category.delete", "商品分类删除");
    register("catalog.item.create", "点单目录项新建");
    register("catalog.item.update", "点单目录项修改");
    register("catalog.item.delete", "点单目录项删除");
    register("resource.create", "资源新建");
    register("resource.update", "资源修改");
    register("resource.occupy", "资源占用");
    register("resource.release", "资源释放");
    register("resource.cleaning.update", "包厢清洁状态切换");
    register("resource.roomtype.create", "房型新建");
    register("resource.roomtype.update", "房型修改");
    register("resource.roomtype.delete", "房型删除");
    register("reservation.create", "预约创建");
    register("reservation.confirm", "预约确认");
    register("reservation.arrival", "预约到店");
    register("reservation.assign_room", "预约分配包厢");
    register("reservation.open_table", "预约开台");
    register("reservation.cancel", "预约取消");
    register("member.create", "会员新建");
    register("member.update", "会员修改");
    register("wallet.recharge", "储值充值");
    register("wallet.refund", "储值退还");
    register("wallet.consume", "储值消费");
    register("points.adjust", "积分调整");
    register("tenant.config.update", "租户配置修改");
    register("tenant.currency.update", "修改租户币种");
    register("tenant.store.create", "门店新建");
    register("tenant.store.update", "门店修改");
    register("tenant.organization.create", "组织新建");
    register("iam.role.create", "角色新建");
    register("iam.role.permissions.assign", "角色权限分配");
    register("iam.role.permission.toggle", "角色权限启停");
    register("iam.user_role.assign", "账号角色分配");
    register("iam.user_role.revoke", "账号角色撤销");
    register("iam.approval.submit", "审批提交");
    register("iam.approval.approve", "审批通过");
    register("iam.approval.reject", "审批驳回");
    register("staff.create", "员工新建");
    register("staff.update", "员工修改");
    register("staff.delete", "员工删除");
    register("media.image.upload", "图片上传");
    register("media.image.delete", "图片删除");
    register("report.export", "报表导出");
    register("auth.login", "后台登录");
    register("auth.logout", "后台登出");
    register("auth.password.change", "密码修改");
    register("context.select", "运营上下文切换");
    register("audit.retention.archive", "审计月份归档登记");
    register("audit.retention.partition.add", "审计分区预建");
    register("admin.operation", "后台操作");
    // IM 后台用户管理动作（模块码 im-user 无法由「模块+动作」自动组合出中文，必须显式登记）。
    register("im-user.delete", "删除 IM 用户");
    register("im-user.status.update", "IM 用户状态变更");
  }

  private AuditActions() {}

  private static void register(String code, String label) {
    EXPLICIT_LABELS.put(code, label);
  }

  /** 动作码 → 中文标签：显式登记优先，其次按模块/动作组合推导，最后退化为码本身。 */
  public static String labelOf(String code) {
    if (code == null || code.isBlank()) {
      return "";
    }
    String explicit = EXPLICIT_LABELS.get(code);
    if (explicit != null) {
      return explicit;
    }
    String[] parts = code.split("\\" + SEPARATOR);
    StringBuilder label = new StringBuilder();
    for (int index = 0; index < parts.length; index++) {
      String segment = parts[index];
      String segmentLabel = (index == parts.length - 1 ? VERB_LABELS : MODULE_LABELS).get(segment);
      if (segmentLabel == null) {
        continue;
      }
      if (label.length() > 0) {
        label.append('-');
      }
      label.append(segmentLabel);
    }
    return label.length() == 0 ? code : label.toString();
  }

  /** 已登记动作码清单（含中文标签），供前端筛选项与运维核对「已接入哪些动作」。 */
  public static List<Map<String, String>> catalog() {
    List<Map<String, String>> items = new ArrayList<>(EXPLICIT_LABELS.size());
    EXPLICIT_LABELS.forEach((code, label) -> items.add(Map.of("code", code, "label", label)));
    return items;
  }
}
