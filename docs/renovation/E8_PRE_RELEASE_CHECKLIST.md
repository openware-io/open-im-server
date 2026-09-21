# E8 预发布、灰度与上线清单

> 依据 SAAS_PLATFORM_07_EXECUTION.md §E8。状态标记：✅ 已完成 / 🟡 待办（占位）。

## 1. 数据库与迁移

| 条目 | 状态 | 说明 |
| --- | --- | --- |
| 空库初始化（Flyway 全量迁移） | 🟡 | 执行各服务 db/migration 前向脚本，验证空库可建齐 schema |
| 默认租户/组织/门店/角色种子 | ✅ | platform-tenant-service V3__seed_default_init.sql |
| Feature Flag 灰度种子 | ✅ | platform-tenant-service V4__seed_feature_flags.sql（feature.ktv.enabled=true、feature.online-payment.enabled=false，按 tenant/store 维度） |
| 旧数据回填演练（C 端预约/账号迁移） | 🟡 | 参照 scripts/migration/reservation-backfill.sql，回填后校验 tenant_id 归属 |
| 前向修复迁移 | 🟡 | 预发布演练中发现的 schema 缺陷以新版本号追加，不修改已执行 Flyway |
| 备份恢复演练 | 🟡 | 演练前 mysqldump 备份、演练后恢复，并校验恢复后数据一致性 |

## 2. 全链路 E2E

| 条目 | 状态 | 说明 |
| --- | --- | --- |
| KTV 主闭环 E2E 冒烟脚本 | ✅ | scripts/verify/e2e-ktv-smoke.ps1（租户初始化→资源→开台→计时→点服务人员→结台→组合收款→日结，占位） |
| 权限隔离 E2E | 🟡 | 服务员越权 order.settle / payment.collect 返回 403 |
| 租户隔离 E2E | 🟡 | 跨租户越权返回 TENANT_SCOPE_DENIED |
| 支付（现金）E2E | 🟡 | 现金收款 + 交班对账 difference_amount=0 |
| Admin/B App/C App 联调 | 🟡 | 三端调用同源 /api/v1/business/** 端点 |

## 3. 灰度与上线

| 条目 | 状态 | 说明 |
| --- | --- | --- |
| Feature Flag 灰度开关 | ✅ | tnt_tenant_config 灰度项，租户默认 + 门店覆盖两级 |
| 灰度维度控制 | ✅ | tenant（store_id=0）/ store（store_id>0）两级，见 V4 注释 |
| 监控与告警生效 | 🟡 | 依赖 Observability 落地（见 docs/standards/40_OBSERVABILITY_CONVENTIONS.md） |
| 渠道生产验证 | 🟡 | 支付宝/微信/Stripe 后置专项，默认关闭 |
| 密钥轮换 | 🟡 | 渠道密钥/支付密钥轮换演练 |
| 应急预案 | 🟡 | 回滚/熔断/降级预案 |
| 人工退款流程演练 | 🟡 | 退款申请/审批/线下退款（财务权限） |

## 4. 完成判定

E8 完成需满足：所有 SAAS_PLATFORM_01 §17.4 完成标准与 SAAS_PLATFORM_06 §13 特殊验收均通过；本清单 🟡 项全部转 ✅ 后视为 E8 达标。
