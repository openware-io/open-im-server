# A380 权限链路与发布一致性设计

## 目标

修复 A380 后台因身份服务旧镜像导致的“无权限”假象，并让认证、上下文、权限快照和发布制品具备可验证的一致性。

## 已确认根因

ACK 的 `platform-identity-service` 运行 `:2.0.4` 旧镜像。该镜像只暴露 Bearer 模式的 `/auth/contexts` 与 `/auth/context/select`，不暴露当前 H5 所依赖的 Cookie 会话 `/auth/session`、CSRF `/auth/csrf` 和退出 `/auth/logout`。A380 H5 将 404 捕获为未登录，随后把所有失败统一渲染成“无运营权限”。

## 设计

1. 身份服务以 HttpOnly `saas_session` 会话为唯一浏览器认证状态；上下文选择必须验证会话、CSRF 和 IAM 快照。
2. 真实无角色、会话失效、上下文服务不可用分别返回稳定错误码，不再把服务异常降级为空权限或伪造上下文令牌。
3. ACK 发布脚本在 rollout 后校验 Deployment 期望镜像和 Pod 实际镜像标签，且对身份会话端点执行 smoke test，阻断旧制品上线。
4. 账号主键语义保持明确：IDaaS subject、IM user id、SaaS account id 分离；OAuth/SSO 必须最终解析到 SaaS canonical account id 后再查询 IAM。

## 兼容性

保留现有 `/auth/contexts` 与 `/auth/context/select` 路径和角色编码，A380、IM 后台、SaaS 后台无需重新授权。仅拒绝错误会话/服务异常，正常角色权限不变。

## 验证

服务端 Maven 全量测试、前端构建检查、ACK rollout、HTTP 端点 smoke test，以及 qian001 的 `IM im_158 -> SaaS account 108 -> tenant 100/store 100` 数据链路核对。
