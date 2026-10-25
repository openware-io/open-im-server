# 前端 UI 风格标准（SaaS B/C）

> 状态：`current`
> 适用：`open-saas-admin`（B 端）、`open-saas-mobile`（C 端）
> 基线：KTV 业务页；酒店及其他集团业务必须复用

## 1. 目标

同一集团下多业务（KTV、酒店、酒吧等）保持统一视觉语言：

```text
同一套 design tokens
同一套页面骨架和卡片
同一套状态/标签/按钮语义
禁止业务页各自定义主色、圆角、阴影
```

## 2. 术语与前置假设

```text
design token：颜色、间距、圆角、阴影的单一来源
B 端：SaaS 管理后台 open-saas-admin
C 端：H5 用户端 open-saas-mobile/c-end
业务页：酒店、KTV、酒吧等具体业态页面
前置假设：页面已有租户/门店上下文，不重复实现登录与授权
```

## 3. B 端标准

### 3.1 页面骨架

```text
根节点 class="admin-page"
页头 class="page-header"：h2 20px/600 + 说明 13px + 右侧操作
内容块 class="admin-card"：白底、1px 边框、var(--admin-radius)、20px padding、轻阴影
指标 class="metric-grid" + "metric-card"
筛选 class="filter-bar"
表格 Element Plus el-table border stripe
```

### 3.2 组件规则

```text
主操作 el-button type="primary"
次操作 default/link
状态 el-tag + 业务字典
分页 admin-pagination
禁止 div 手写页面级卡片
禁止自定义主色/圆角/阴影
```

### 3.3 指标卡

```text
桌面 4 列；<=640px 2 列；<=420px 1 列
数字 26px/600，标签和说明 13px muted
卡片与 admin-card 同圆角、同阴影
```

## 4. C 端标准

### 4.1 Design tokens

```text
--primary #13b981   --primary-dark #079668
--navy #273f73      --text #1b2430
--muted #7b8492     --line #e9ebef
--bg #f5f6f8        --card #ffffff
--danger #ff654e    --radius 18px
```

### 4.2 组件

```text
page-head       固定页头：返回 / 标题 / 右侧动作
venue-card      列表卡：封面 + 文案
hotel-cover     酒店封面占位（与 KTV 图片卡同尺寸）
detail-card     详情卡：标题、地址、标签
form-card       表单卡：label + input/select + primary-button
tag-row         标签胶囊
status-*        状态胶囊
```

### 4.3 业务规则

```text
列表用 venue-card
详情用 detail-card + tag-row
订单状态用 tag-row，不用裸文本
配置读取走 /api/v1/business/tenant/config
禁止调用 /api/v1/admin/tenant/config
禁止新增与 tokens 冲突的局部色值
```

## 5. 模板示例

```html
<section class="page">
  <header class="page-head"><h1>酒店管理</h1></header>
  <div class="metric-grid">
    <article class="metric-card"><span>今日入住</span><strong>12</strong></article>
  </div>
  <div class="hotel-cover">...</div>
</section>
```

## 6. 验收

```text
B 页面无自定义主色/圆角/阴影
B 指标卡在 KTV/酒店一致
C 列表/详情/订单使用统一组件
C 控制台无 401/403/JS error
同一业务动作在 B/C 的文案和状态色一致
```

## 7. 参考实现

```text
open-saas-admin/src/styles/global.css
open-saas-admin/src/views/tenant/ktv-config.vue
open-saas-admin/src/views/tenant/hotel.vue
open-saas-mobile/c-end/styles.css
open-saas-mobile/c-end/app.js
open-saas-mobile/c-end/hotel-real.js
```

## 8. 自动校验

```text
scripts/validate/validate-frontend-ui-standard.ps1
```

检查 B 端 `admin-page/admin-card/metric-grid`、C 端 tokens/`hotel-cover`/`tag-row`，
并禁止 C 端调用 `/api/v1/admin/tenant/config`。已注册到 `invoke-engineering-validation.ps1`。

## 9. 与其他规范的关系

- [DDD 服务工程规范](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)：页面只消费已定义的服务契约
- [E2E 测试与验收规范](43_E2E_TESTING_CONVENTIONS.md)：UI 验收与真实链路证据
- 前端实现：open-saas-admin/src/styles/global.css（外部仓库，不在本文档树内）
