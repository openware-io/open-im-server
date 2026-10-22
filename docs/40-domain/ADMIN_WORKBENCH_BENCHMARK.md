# 后台工作台业界对标评估

[中文](ADMIN_WORKBENCH_BENCHMARK.md) | [English](ADMIN_WORKBENCH_BENCHMARK.en.md)

> 状态：`draft`（2026-10-22，方案评审输入）  
> 目的：用成熟产品的后台信息架构校验我们的四工作区模型，并识别缺口。  
> 结论只影响补强项，不推翻已确认的平台/总部/门店/IM 骨架。

## 1. 对标对象

| 产品 | 类型 | 借鉴点 | 不照搬 |
| --- | --- | --- | --- |
| Shopify Admin | 多店电商后台 | 店铺切换、员工权限、应用/模块生态 | 电商单业态，不覆盖酒店/ KTV 履约 |
| Square Dashboard | 多门店经营后台 | 位置切换、团队权限、任务型首页 | 偏支付/零售，复杂业态深度不足 |
| Toast | 餐饮门店运营 | 门店/总部区分、前台任务、角色权限 | 餐饮单业态，不能直接套酒店 |
| Mews / Cloudbeds | 酒店 PMS | 物业切换、前台/预订/房态/报表分层 | 单业态，不覆盖多业态集团 |
| Oracle OPERA / Agilysys | 酒店企业级 | 集团-物业-角色-任务链路 | 过重，MVP 不照搬 |
| Odoo / SAP Fiori | 多应用/角色工作中心 | 模块注册、角色工作中心 | 应用/磁贴过多，容易变成功能目录 |

参考入口：

- [Shopify staff permissions](https://help.shopify.com/en/manual/your-account/staff-accounts)
- [Square team permissions](https://squareup.com/help/us/en/article/5096-set-up-team-permissions)
- [Toast back office](https://pos.toasttab.com/products/back-office)
- [Mews PMS](https://www.mews.com/en/products/pms)
- [Oracle OPERA](https://www.oracle.com/hospitality/hotel-property-management/)
- [Odoo apps](https://www.odoo.com/apps)
- [SAP Fiori](https://www.sap.com/products/technology-platform/fiori.html)

## 2. 对标矩阵

评分：`强 / 中 / 缺`。评分对象是“当前方案 + 原型”，不是最终实现。

| 维度 | 行业做法 | 我们的现状 | 判断 |
| --- | --- | --- | --- |
| 组织层级 | 集团/品牌/区域/门店/岗位 | Platform / Tenant / Store / IM | 强 |
| 角色落点 | 登录后按角色进工作台 | 原型已按角色切换；正式实现待补 | 中 |
| 多业态 | 业务组合 + 业态切换 | 总部业务卡 + 门店业态切换 | 强 |
| 模块扩展 | 应用/模块注册，按授权显示 | `businessModule` 已设计，未实现 | 中 |
| 权限可见性 | 菜单由服务端按权限返回 | RBAC 已有；角色档案/菜单契约待补 | 中 |
| 任务型首页 | 前台/收银看任务，不看看板 | 原型已区分店长/前台 | 强 |
| 多门店汇总 | 总部“全部门店”视图 + 排名/下钻 | 有排名，缺 All locations 汇总切换 | 中 |
|  onboarding | 新租户/新门店开通清单 | 缺 | 缺 |
| 权限粒度 | 角色模板 + 自定义权限 + 门店范围 | 固定角色 + 权限，缺角色模板/自定义 | 中 |
| 移动端 | 与后台同一角色/任务模型 | B 端移动前台有原型，未与角色模型打通 | 中 |
| 搜索/快捷入口 | 全局搜索、最近访问、收藏 | 缺 | 缺（非 MVP） |
| 支持模式 | 平台以租户身份排障，带审计 | 设计中有，未实现 | 中 |

## 3. 结论

### 站得住的部分

1. **四工作区**与 Toast（总部/门店）、Mews（物业/前台）、Shopify（店铺切换）一致；
2. **总部业务组合 + 门店业态切换**比“所有业务塞一个侧栏”更接近多业态集团的实际用法；
3. **店长看经营、前台看任务**符合酒店/餐饮行业成熟产品的角色分工；
4. **模块注册**方向正确，能避免每加一个业态就改后台壳。

### 需要补强的部分

按优先级：

```text
P0 角色档案 + 登录落点 + 服务端菜单
P1 总部“全部门店”汇总视图
P2 角色模板 + 门店范围授权
P3 新租户/新门店 onboarding 清单
P4 业务开通/订阅驱动的模块可见性
P5 移动端角色/任务模型对齐
P6 全局搜索/最近访问（非 MVP）
```

## 4. 具体建议

### 4.1 总部增加“全部门店”

行业里总部首页通常是组合视图，不是单店视图。建议：

```text
总部 → 经营总览
  范围：[全部门店] [酒店] [KTV] [单店]
  集团营收 / 入住率 / 开台率 / 异常
  门店排名 → 点击下钻单店
```

当前原型的业务组合卡片保留，但增加“全部门店”范围切换。

### 4.2 角色模板 + 自定义角色

行业成熟产品通常提供：

```text
角色模板：平台管理员、租户管理员、店长、前台、收银、财务、保洁
角色范围：平台 / 租户 / 门店
权限：页面 + 操作
```

我们已有 `iam_role` / `iam_permission`，缺的是：

- 角色模板；
- 角色档案（工作区/首页/导航）；
- 门店范围授权 UI；
- 自定义角色的边界和审计。

### 4.3 新租户/新门店 onboarding

Shopify/Square 都有开通清单。建议增加：

```text
新租户：
  创建租户 → 选择业态 → 创建门店 → 分配角色 → 配置支付 → 首单

新门店：
  门店资料 → 开通业态 → 房间/包厢 → 员工角色 → 营业时间 → 首单
```

没有 onboarding，新账号登录后只会看到空看板。

### 4.4 业务开通驱动模块

```text
租户订阅/开通酒店 → 酒店模块可见
未开通 → 业务组合显示“可开通”，不显示酒店菜单
```

这与行业“应用/模块授权”一致，也避免“菜单可见、点进去无权”。

### 4.5 权限到动作

行业后台通常把权限拆到动作级：

```text
hotel.booking.view
hotel.booking.create
hotel.checkin
hotel.checkout
hotel.rate.update
```

菜单只控制入口，按钮/接口再做动作级校验；否则会出现“能进页面但不能操作”的尴尬。

### 4.6 移动端同一模型

B 端移动前台不应另建一套 IA，应复用：

```text
角色 → 工作区 → 首页 → 任务
```

移动端只裁减模块，不改变角色语义。

## 5. 不建议照搬

- **Odoo 式应用目录**：模块太多会变成功能迷宫；
- **SAP Fiori 式磁贴工作中心**：企业级但过重，MVP 不需要；
- **Oracle OPERA 式全量酒店功能**：功能深但学习成本高；
- **Shopify 单店切换直接套多业态**：我们还需要“业态”维度；
- **把所有权限做成一个超级管理员**：会破坏角色工作台。

## 6. 对已确认方案的影响

不变：

```text
Platform / Headquarters / Store / IM 四工作区
角色首页
酒店/ KTV 门店工作台
业务组合 + 业态切换
```

补强：

```text
All locations 汇总
角色模板 + 门店范围
onboarding
业务开通驱动
动作级权限
移动端对齐
```

## 7. 相关

- [后台角色工作台与信息架构](ADMIN_WORKBENCH.md)
- [ADR-0013：角色工作台作为平台能力](../20-architecture/adr/0013-role-workbench.md)
- [酒店实现方案](hotel/HOTEL_IMPLEMENTATION_PLAN.md)
