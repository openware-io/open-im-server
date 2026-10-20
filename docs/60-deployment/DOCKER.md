# Docker 一键部署

在项目根目录维护 `.env`，所有密码、数据库名和 JWT 配置均从该文件读取。Docker Compose 会覆盖基础组件地址与服务间地址，使容器通过内部 DNS 通信；不要在 `.env` 中将 `DB_HOST`、`REDIS_HOST` 等本地地址改为容器地址。

首次部署或需要清空本项目 Docker 数据卷时执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-docker.ps1 -ResetData
```

日常构建并更新容器（脚本会先执行全工程 `mvn clean package`，再打包对应 JAR 到精简 JRE 镜像）：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-docker.ps1
```

停止容器但保留数据卷：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-docker.ps1 -Stop
```

部署包含 MySQL 8、Redis 7、MongoDB 7、RocketMQ 5、MinIO，以及 user、message、conversation、support、order、admin、access-ws、gateway 八个应用服务。脚本自动启用 `media-local` 配置组并初始化私有媒体 Bucket；仅暴露网关端口（默认 `3002`）及本地调试所需的基础设施端口，服务间请求全部走 Docker 内部网络。脚本会等待 `http://127.0.0.1:3002/actuator/health` 返回 `UP`。
