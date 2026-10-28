# 酒店 B/C 前端 UI 风格标准

> 2026-10-24
> 正式标准：[32_FRONTEND_UI_STYLE.md](../../80-development/standards/32_FRONTEND_UI_STYLE.md)
> 基线：KTV B 端 `open-saas-admin` / C 端 `open-saas-mobile`
> 适用：酒店 B 端前台、C 端酒店目录/详情/订单

## 1. B 端（管理后台）

### 页面骨架

```text
根节点：class="admin-page"
页头：class="page-header"（h2 20px/600，说明 13px muted）
内容块：class="admin-card"（白底、1px 边框、var(--admin-radius)、20px padding、轻阴影）
指标：class="metric-grid" + "metric-card"
筛选：class="filter-bar"
表格：Element Plus el-table border stripe
按钮：el-button，主操作 type="primary"，次级 default/link
```

### 约束

```text
禁止在业务页自定义主色/圆角/阴影变量
禁止用 div 手写页面级卡片替代 admin-card
状态统一 el-tag + 业务字典
页头只保留标题、说明、右侧操作
```

参考实现：

```text
src/views/tenant/ktv-config.vue
src/views/tenant/hotel.vue
src/styles/global.css
```

酒店页与 KTV 页共用同一套 `admin-page/admin-card/metric-grid/filter-bar`，新增业务只允许在 `admin-card` 内组合组件。

## 2. C 端（H5）

### Design tokens（styles.css :root）

```text
--primary       #13b981
--primary-dark  #079668
--navy          #273f73
--text          #1b2430
--muted         #7b8492
--line          #e9ebef
--bg            #f5f6f8
--card          #ffffff
--danger        #ff654e
--radius        18px
```

### 组件

```text
page-head       固定页头，左返回/中标题/右动作
venue-card      列表卡：封面 + 文案；酒店用 hotel-cover 占位
detail-card     详情卡：merchant/标题/地址/标签
form-card       表单卡：label + input/select/button
tag-row         标签胶囊（即时确认/到店付/免费取消）
primary-button  主按钮
status-*        状态胶囊颜色
```

### 酒店约束

```text
酒店列表必须使用 venue-card + hotel-cover
酒店详情必须使用 detail-card merchant + hotel-detail-head + tag-row
订单状态用 tag-row，不用裸文本
不新增与 styles.css 冲突的局部色值
配置读取走 /api/v1/business/tenant/config，不得调用 /admin/tenant/config
```

参考实现：

```text
c-end/styles.css
c-end/hotel-real.js
c-end/app.js（KTV 业务）
```

## 3. 验收清单

```text
B 酒店页无自定义主色/圆角
B 酒店页与 KTV 页使用相同 page-header/admin-card
C 酒店列表有封面卡和 tag-row
C 酒店详情有 detail-card + tag-row
C 订单状态使用 tag-row
C 端不再请求 /admin/tenant/config
浏览器 console 无 401/403/JS error
```

## 4. 本轮落地

```text
B saas-admin:
  .metric-grid/.metric-card 提升到 global.css
  酒店页指标卡与 KTV/门店页一致
  控制台 0 错误（hotel-b-ui-standard.png）

C saas-mobile:
  hotel-cover / hotel-detail-head / tag-row
  列表、详情、订单状态统一
  配置改走 /api/v1/business/tenant/config
  控制台 0 错误（hotel-c-ui-standard.png / hotel-c-detail-standard.png）
```

## 5. 响应式验收

```text
B 1440x900：4 列指标卡 + 标准 admin-card/表格
C 390x844：venue-card 封面卡 + tag-row 不溢出
证据：hotel-b-ui-desktop.png / hotel-c-ui-mobile.png
```
