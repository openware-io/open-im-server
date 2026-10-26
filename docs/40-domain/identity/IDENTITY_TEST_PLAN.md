# 身份与酒店业务验收测试计划

> 状态：`accepted target`（2026-10-23）
> 适用：ADR-0015 落地后的浏览器端、真机 App、C/B 全链路验收。
> 原则：真实环境、真实账号、真实支付/退款；禁止 mock、假成功、测试专用后门。

## 1. 测试范围

```text
SaaS B Web（open-saas-admin）
C 端 H5/小程序（OpenChat 运营服务入口）
IM Android 真机（服务入口、OAuth、C 端）
SaaS B App/移动端（前厅）
后端 API、数据库、支付/退款、消息/通知
```

不在范围：

```text
未接入的第三方渠道
未实现的在线支付渠道
生产真实资金扣款（使用支付沙箱/测试商户）
```

## 2. 环境

| 环境 | 地址/设备 | 用途 |
| --- | --- | --- |
| Kind 集群 | `open-im-local` | 服务与网关 |
| Ingress | `http://127.0.0.1:30080` | Web/API |
| SaaS B Web | `http://127.0.0.1:30080/saas/` | 浏览器 |
| Android | 真机，USB/无线 ADB | IM App、B App |
| iOS | 可用真机时追加 | 同等用例 |
| 数据库 | Kind MySQL | 真实数据 |
| 支付 | payment-service 测试商户 | 真实现金/在线退款 |

真机必须能访问宿主机 `30080`；不能用模拟器截图替代真机证据。

## 3. 账号与数据

```text
平台管理员：admin / Admin@123
租户 owner：tenant-admin / Admin@123
门店经理：mobile.manager5 / Mobile@123
酒店门店：businessType=HOTEL（不得挂在 KTV 门店）
C 客户：真实 OAuth 首次授权创建
B 员工：租户管理员邀请/绑定，未绑定账号用于拒绝测试
```

测试数据要求：

```text
酒店/房型/房价/库存为真实 API 创建
订单号、支付单号、退款单号来自真实服务
不得手工改库伪造状态
```

## 4. 浏览器 B Web 用例

### 4.1 身份与权限

```text
BROWSER-B-001 平台管理员登录，进入平台工作区
BROWSER-B-002 租户 owner 登录，只看到本租户
BROWSER-B-003 门店经理登录，只看到授权门店
BROWSER-B-004 无权限角色访问酒店菜单 → 403/不可见
BROWSER-B-005 租户 A 直接改 URL 访问租户 B 资源 → 404/403
BROWSER-B-006 平台运营默认不能进租户业务
```

### 4.2 酒店业务

```text
BROWSER-B-010 酒店/房型/房价/库存配置
BROWSER-B-011 报价：日期、人数、房型
BROWSER-B-012 创建预订，库存扣减，订单号生成
BROWSER-B-013 确认/取消/no-show
BROWSER-B-014 办理入住，分配房间
BROWSER-B-015 押金收取，账单记账
BROWSER-B-016 现金退款，账单 REFUND
BROWSER-B-017 在线退款申请 PENDING
BROWSER-B-018 在线退款同步 REFUNDED，账单一致
BROWSER-B-019 续住/换房
BROWSER-B-020 退房结算，房态释放
BROWSER-B-021 客房清扫开始/完成
BROWSER-B-022 夜审
BROWSER-B-023 退款金额超过可退 → 拒绝
BROWSER-B-024 重复提交/幂等
```

## 5. C 端 H5/小程序用例

```text
BROWSER-C-001 运营服务展示已安装、已授权酒店入口
BROWSER-C-002 未安装/无权益 → 不展示
BROWSER-C-003 OAuth 授权页展示 scope
BROWSER-C-004 同意后回调，SaaS C 会话建立
BROWSER-C-005 拒绝授权 → 不建会话
BROWSER-C-006 code 重放 → 失败
BROWSER-C-007 PKCE/state 错误 → 失败
BROWSER-C-008 酒店列表/详情/报价
BROWSER-C-009 创建预订，库存真实扣减
BROWSER-C-010 支付（真实测试商户）
BROWSER-C-011 我的订单，只看到本人订单
BROWSER-C-012 取消/退款状态真实
BROWSER-C-013 C 会话调 B API → 403
BROWSER-C-014 未绑定客户首次授权建 customer
BROWSER-C-015 同一账号在 A/B 租户身份不串
```

## 6. IM Android 真机用例

### 6.1 入口与授权

```text
APP-001 安装 APK，IM 登录
APP-002 服务 Tab 展示酒店应用
APP-003 点击进入 SaaS H5/小程序
APP-004 GVBridge.login 拉起授权
APP-005 授权成功回到业务页
APP-006 取消授权回退
APP-007 杀进程重启，会话保持/正确失效
APP-008 切换 IM 账号，C 会话不串
APP-009 IM JWT 不出现在 URL/WebView 日志
```

### 6.2 C 端业务

```text
APP-010 酒店列表/详情
APP-011 报价/预订
APP-012 支付
APP-013 我的订单
APP-014 断网/弱网重试
APP-015 支付回跳/订单状态恢复
```

### 6.3 B 端前厅

```text
APP-020 B 员工授权进入前厅
APP-021 未绑定员工 → 403
APP-022 今日任务/到店/在住/离店
APP-023 入住/换房/续住
APP-024 押金/账单
APP-025 现金退款
APP-026 在线退款申请/同步
APP-027 退房
APP-028 房态/清扫
APP-029 夜审
APP-030 B 会话调 C API → 403
```

## 7. 安全与隔离用例

```text
SEC-001 跨租户 URL/ID 遍历
SEC-002 跨门店 ID 遍历
SEC-003 客户端改 tenantId/storeId
SEC-004 客户端改 role/permission
SEC-005 C/B cookie 混用
SEC-006 平台代操作无 grant
SEC-007 代操作过期
SEC-008 离职员工旧会话失效
SEC-009 角色变更 authorization_version 失效
SEC-010 PII 未授权不明文返回
```

## 8. 异常与恢复

```text
ERR-001 支付服务不可用，订单不被标记 paid
ERR-002 退款服务不可用，保持 PENDING
ERR-003 重复回调幂等
ERR-004 库存不足回滚
ERR-005 并发预订超卖为 0
ERR-006 网关 502/超时，前端真实错误
ERR-007 数据库迁移失败回滚
ERR-008 服务重启后会话/订单一致
```

## 9. 证据要求

每个用例：

```text
用例 ID
环境/设备/版本
账号/租户/门店
请求 traceId
截图或录屏
关键 API 响应
数据库核对（订单/支付/退款/账单）
结果 PASS/FAIL
```

真机：

```text
adb devices
adb logcat 关键片段
APK 版本/commit
```

浏览器：

```text
Chrome/Edge 版本
Network HAR
Console 无未处理异常
```

禁止：

```text
只用单元测试代替 E2E
只用接口测试代替 UI
用 mock 截图
手工改库造状态
```

## 10. 用例分级矩阵

| 用例组 | 级别 | 预期 | 证据 | DB 校验 |
| --- | --- | --- | --- | --- |
| SEC-001..010 | P0 | 越权全部 403/404 | HAR/logcat/trace | 无跨租户行 |
| BROWSER-B-005/006 | P0 | 跨租户/平台默认进入被拒 | HAR | tenant 不变 |
| BROWSER-C-006/007/013 | P0 | code 重放/PKCE 错/C token 调 B 均拒绝 | HAR/trace | 无新会话 |
| APP-021/030 | P0 | 未绑定员工/B 调 C 被拒 | logcat/trace | 无 membership |
| BROWSER-B-016/017/018/023 | P0 | 现金/在线退款账实一致，超退拒绝 | HAR/DB | folio/refund 一致 |
| APP-026/027 | P0 | 真机退款/退房成功 | 录屏/logcat/DB | 订单/账单一致 |
| ERR-001/002/004/005 | P0 | 失败不假成功、库存不超卖 | trace/DB | 订单/库存一致 |
| BROWSER-B-001..004/010..015/019..022/024 | P1 | 主流程通过 | HAR/录屏 | 订单/库存/账单 |
| BROWSER-C-001..005/008..012/014/015 | P1 | C 主流程通过 | HAR/录屏 | 订单/客户 |
| APP-001..009/010..015/020/022..025/028/029 | P1 | 真机主流程通过 | logcat/录屏 | 订单/会话 |
| ERR-003/006/007/008 | P1 | 幂等/可恢复 | trace/DB | 无重复/可回滚 |

P2（不阻塞签字，但必须记录）：UI 文案、非关键动效、非核心排序。

## 10.1 固定 fixture

测试前用真实 API 预置并记录：

```text
租户 A / 租户 B
HOTEL 门店 A / HOTEL 门店 B
KTV 门店（用于 businessType 隔离）
酒店/房型/房价/库存
B 员工（已绑定/未绑定）
C 客户（新/老）
现金/在线支付测试商户
```

每个 fixture 记录 ID、创建接口、traceId；不得手工改库。

## 10.2 阶段映射

```text
S2：membership/role_binding API + 拦截器测试、C/B 拒绝、跨租户
S3：应用安装/权益/服务入口
S4：OAuth C/B 会话、legacy 回退、code 重放
S5：酒店门店、C/B 应用、酒店全链路
S6：旧模型迁移、离职回收、authorization_version
E2E 阶段（S1–S6 完成后，不占用 S7 代号）：浏览器 + 真机全量 E2E、异常恢复、回归
```

## 10.3 与硬门禁待清偿项对账

| 待清偿项 | 关闭阶段 | 证据 |
| --- | --- | --- |
| 酒店 C 端 mock | S5 | mock 文件删除/无生产引用 |
| tnt_membership 拦截器 | S2 | 四组上下文测试 |
| OauthLink legacy 回退 | S4 | 老用户/C→B 测试 |
| account_type 旧值 | S2 | 迁移 + 代码/测试 |
| 旧 iam_user_role | S6 | 冻结/迁移报告 |
| flyway 9 条红灯 | S5 | GATE-FLYWAY-LEGACY-001 正式例外，2026-11-15 到期；禁止改已执行迁移 |
| tenant-sql 3 条红灯 | S2 已关闭 | RoleMapper/Membership/IamSnapshot 补 tenant-agnostic 注释 |

## 10.4 fixture 与真值落盘

每个 fixture 必须记录：

```text
fixture 名称
创建接口 + 请求体摘要
X-Request-Id
租户/门店/员工/客户/房型/库存 ID
创建时间
清理方式
```

数据库真值查询至少覆盖：

```text
tnt_membership / iam_role_binding
cst_member.membership_id
ord_order / payment / refund / folio
htl_inventory_calendar / htl_inventory_hold
authorization_version
```

## 10.5 执行与证据

```text
执行人：主任务实现者 + 仲裁代理抽检
证据目录：artifacts/identity-acceptance/<yyyyMMdd>/<CASE-ID>/
保留期：至少到分支合并后 90 天
抽检：仲裁代理按目录随机抽 P0/P1，缺证据即 FAIL
```


## 11. 准入/准出

准入：

```text
S1–S6 完成
迁移在空库/增量库通过
服务部署到 Kind
酒店 HOTEL 门店数据就绪
支付测试商户可用
真机可访问 ingress
```

准出：

```text
P0/P1 用例 100% PASS
P2 无阻塞项
无 mock/假成功
无跨租户/跨门店越权
支付/退款账实一致
仲裁代理签字 PASS
```

## 12. 执行顺序

```text
1. 后端契约/集成
2. 浏览器 B Web
3. 浏览器 C H5
4. Android C 端
5. Android B 端
6. 安全/隔离
7. 异常/恢复
8. 全链路回归
```

## 13. 相关

- [身份与访问模型](IDENTITY_ACCESS_MODEL.md)
- [改造硬门禁](IDENTITY_GUARDRAILS.md)
- [落地清单](IDENTITY_ROLLOUT.md)
- [ADR-0015](../../20-architecture/adr/0015-identity-federation-tenant-iam.md)
