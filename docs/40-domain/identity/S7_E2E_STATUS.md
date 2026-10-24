# S7 E2E 执行状态

> 环境：Kind `open-im-local`、Chrome 155、Xiaomi MI 9（adb `c4c90621`）
> 时间：2026-10-24
> 状态：进行中，未通过

## 1. 已确认

### 浏览器

```text
http://127.0.0.1:30080/saas/ -> 200
平台管理员 admin 登录成功
-> /saas/#/admin/platform/tenants
```

### Android

```text
adb install -r app-debug.apk -> Success
package io.openware.openchat
启动进入登录页
```

### BFF 上下文问题

`a380-admin` 的 `cons_admin_account.platform_account_id` 仍是旧值 100，
tenant BFF `/internal/iam/account/100/contexts` 返回空：

```text
GET /api/v1/admin/contexts -> {"items":[]}
```

已改为 identity account `101`，重新登录返回 `platformAccountId=101`；
浏览器完整上下文选择尚未复测。

## 2. 未通过项

```text
B Web 租户上下文选择 / 酒店页面
C H5 会话重建与酒店下单页面
Android 登录 + 酒店入口
C/B 隔离、租户/门店隔离
酒店预订/支付/退款/前台/夜审/房态浏览器/真机全量用例
异常恢复
```

## 3. 已知环境问题

```text
C H5 直接打开 /a380/ 被 OAuth authorize 拒绝（会话过期）
Android 输入法/坐标操作导致登录密码未写入，应用被切到系统搜索
Chrome 无 Playwright driver，使用 CDP 手工驱动
```

## 4. 下一步

```text
1. 重建 C 会话并验证 /a380/ 页面
2. 复测 a380-admin B 上下文选择 -> 酒店页面
3. Android 用 uiautomator 精确定位输入密码并登录
4. 按 IDENTITY_TEST_PLAN 跑 P0/P1 用例
```

本文件不能作为 S7 通过证据。
