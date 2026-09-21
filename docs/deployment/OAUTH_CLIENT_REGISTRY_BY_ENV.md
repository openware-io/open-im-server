# OAuth 客户端注册表（按环境隔离）— Provider open_application

> 原则：**Provider 的客户端回调/注册是环境事实，不是代码/迁移事实。**
> 共享迁移（V19/V21 等）只负责建表与“空壳种子/占位”，**不得写入任何环境的域名**
> （现状 V19 写入了 ACK-dev 域名，属历史坏味道，见文末整改项）。
> 每个环境由其自己的“环境种子/发布脚本”写入本环境的 issuer、回调、origin。

## 各环境取值（现状 + 期望）

| 字段 | Kind（本地联调） | ACK-dev | 生产 |
| --- | --- | --- | --- |
| 网关/API | `http://127.0.0.1:30002`（USB 回环）或 `http://<LAN-IP>:30002` | `https://api.dev.example.com` | 生产域名 |
| A380 H5（C 端） | `http://127.0.0.1:30082/a380/` | `https://miniservice.dev.example.com/a380/` | 生产域名/a380/ |
| B 端 H5 | `http://127.0.0.1:30082/b/`（如需） | `https://miniservice.dev.example.com/b/` | 生产域名/b/ |
| Provider issuer | `http://127.0.0.1:30002` 或 LAN | `https://api.dev.example.com` | 生产域名 |
| `saas-a380-c.callback_url` | `http://127.0.0.1:30082/a380/`（Kind 正确值） | `https://miniservice.dev.example.com/a380/` | 生产对应 |
| `saas-a380-h5.callback_url` | `http://127.0.0.1:30082/b/`（Kind 正确值，按需） | `https://miniservice.dev.example.com/b/` | 生产对应 |

说明：Kind 使用局域网地址作为兼容表主回调，同时在 `user_oidc_redirect_uri` 精确登记局域网、`127.0.0.1` 与 `localhost` 回调，使真机和本机浏览器可并行验证。**Kind 的正确基线就是本地值，不是 ACK 域名**；ACK-dev/生产库仅登记各自 HTTPS 域名。

## 环境种子与恢复
- 每个环境部署时执行 `scripts/deploy/sync-oidc-client-registry.ps1` 的环境种子，幂等覆盖注册表并校验结果，禁止手工零散改库。
- 待办整改（并入 D2 / 环境化收口）：
  1. V19 是已投产 Flyway 历史迁移，不能改写 checksum；V22 回填结构化客户端事实，V23 清除历史回调，部署种子再写入环境地址；
  2. Kind 就绪脚本自动写入 Kind 本地回调（loopback/LAN），ACK 发布脚本写入 ACK 域名；
  3. 种子脚本在部署中回读注册表；发现跨环境值即失败。

## 记录
- Kind 库被修改行：`open_application(app_id='saas-a380-c')` callback_url → 本地回环（用于 USB 联调验证），属 Kind 环境正确配置；ACK/生产库未触碰。
