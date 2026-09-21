# IM + SaaS 回归与发布记录（2026-09-11）

## 本次修复

1. SaaS 运营上下文由服务端 Redis 会话保存 selectedContextId；刷新、深链和多标签页重新读取服务端选择，选择失效时清空上下文并阻止页面带旧授权继续请求。
2. 运营人员列表和增删/启停操作按租户、组织、门店作用域校验；角色绑定投影补齐组织/门店字段，跨范围账号只展示可见角色且禁止全局操作；IAM 查询或撤权失败直接失败，避免半成功。
3. SaaS 窄屏布局增加断点折叠、遮罩、导航后自动收起、表格横向滚动和无上下文提示。
4. 会话上下文更新改为 Redis Lua 原子更新并保留 TTL；Context API 严格校验 contextId、权限版本和非空权限；版本号已更新，OpenAPI 尚待候选服务启动后导出。

## 代码级验证

- `gv_saas_admin`: `npm test`：4 个测试文件、14 个测试通过；`npm run build` 通过。
- `platform-admin-service`：定向安全测试 9 个通过；此前管理员模块全量测试 20 个通过，租户及依赖模块 Maven 测试通过；使用 Kind Redis 的真实会话测试 3 个通过。
- `invoke-engineering-validation.ps1 -Scope Changed`：18:27 完整通过；历史 `.outputs/ui*.xml` BOM 已规范化。后续代码改动仍需重新检查。
- Flutter secret 消息测试 11 个通过；生产配置测试使用 `--dart-define=APP_ENV=prod` 通过，未修改 App 生产逻辑。

## 真机/浏览器回归

- vivo `10AF9Y31YG002M3` 已连接；现有包可启动并显示消息列表、未读角标和底部导航。
- 内置浏览器已完成集团门户 → SaaS 单点登录 → 后台选择 → 租户后台真实导航，页面可见；当前线上页面尚未包含本地候选改动，候选镜像部署后必须复测桌面、窄屏和 A380 C/B 权限链路。
- 既有真机问题仍需在候选版本复测：A380 C 端余额/积分加载失败、IM→SaaS 页面空白、现有 APK 签名不一致导致不能覆盖安装。未卸载用户原包，避免破坏数据。

## 发布门禁与状态

尚未发布。需要先在本地 Kind 用同一候选镜像 digest 部署，执行真实 SaaS/IM 用例并核对测试数据清理；再按 43_E2E 与 RELEASE_RUNBOOK 生成候选清单、ACK 回归和正式发布。Android 本轮无 App 代码变更，不生成新 APK；`C:\Users\liuxi\Downloads\xchpro.jks` 保留给需要构建签名包的后续回归。
