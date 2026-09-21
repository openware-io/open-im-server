# 接口文档

Docker 部署仅通过网关提供一个聚合 Swagger UI：

```text
http://127.0.0.1:3002/swagger-ui.html
```

网关使用 HTTP Basic 认证保护 Swagger UI 和全部 OpenAPI JSON 端点。执行
`deploy-docker.ps1` 前，请在 `.env` 中设置以下值；CI 环境也可以通过进程环境变量提供：

```properties
IM_DOCUMENTATION_USERNAME=docs-admin
IM_DOCUMENTATION_PASSWORD=change-me
```

界面聚合用户、消息、会话、预约和管理后台五个服务的接口定义。各服务端口仅在 Docker
内部网络中开放，接口定义只能通过已认证的网关访问。服务间 `/internal/**` 接口不会出现在
对外接口文档中。

以上为本地开发示例值，生产或测试共享环境必须替换为独立的高强度密码，且不要复用数据库、JWT 或服务间认证密钥。
