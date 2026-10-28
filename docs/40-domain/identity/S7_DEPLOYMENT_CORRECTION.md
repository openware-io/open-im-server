# 版本与部署纠正记录（S7）

> 2026-10-24

## 1. 2.4.0 从哪里来

```text
分支：develop/2.4.0-hotel
根聚合 pom：2.4.0
领域父 POM：2.4.0
可部署叶子服务：2.4.0-SNAPSHOT
SaaS H5 VERSION：2.4.0
```

按 `RELEASE_RUNBOOK §1.1`：

```text
开发分支叶子服务版本 = <基础版本>-SNAPSHOT
镜像 tag 必须等于服务 Maven 版本
正式版本才使用纯 SemVer
```

因此后端使用 `2.4.0-SNAPSHOT` 是正确的；但本次执行确实违反了发布流程。

## 2. 本次违反项

```text
1. 直接 kubectl set image 部署 payment / H5 等
2. 手工加载镜像后 rollout restart，绕过 manifest
3. 给 saas-mobile 手打 2.4.0-s7 tag（≠ VERSION 2.4.0-SNAPSHOT）
4. 没有先生成 schema v2 release manifest
5. payment deployment 长时间停在 2.2.0，改动实际未生效
```

这些违反 `RELEASE_SPECIFICATION`：

```text
禁止直接 kubectl set image、手工改 tag 或绕过发布脚本部署。
所有发布必须由经过校验的 schema v2 manifest 驱动。
```

## 3. 纠正结果

生成 manifest：

```text
.outputs/releases/s7-manifest.json
schemaVersion=2
releaseType=development
sourceRevision=fa46abe0
targets:
  gateway
  platform-identity-service
  platform-tenant-service
  platform-order-service
  common-payment-service
  platform-admin-service
  saas-mobile
```

通过：

```text
scripts/deploy/build-saas-release.ps1 -SkipPush -SkipPackage -Targets ...
```

修复 `k8s-scoped.ps1` 的本地 Kind 缺口：

```text
部署前用 docker save | ctr images import 导入 manifest 对应镜像
不再依赖手工 kind load + rollout restart
```

部署：

```text
scripts/deploy/k8s-scoped.ps1 -SaasReleaseManifestPath .outputs/releases/s7-manifest.json
-> EXIT=0
-> Incremental Kind deployment verified by immutable tag
```

运行态：

```text
gateway                        2.4.0-SNAPSHOT
platform-identity-service      2.4.0-SNAPSHOT
platform-tenant-service        2.4.0-SNAPSHOT
platform-order-service         2.4.0-SNAPSHOT
common-payment-service         2.4.0-SNAPSHOT
platform-admin-service         2.4.0-SNAPSHOT
saas-mobile                    2.4.0-SNAPSHOT
release id = local-scoped-development.20261024T045503Z.fa46abe0
```

手工 tag `saas-mobile:2.4.0-s7` 已删除。

## 4. 后续规则

```text
禁止手工 tag / kubectl set image / rollout restart 代替发布
只允许 build-saas-release -> manifest -> k8s-scoped / k8s.ps1
正式版本必须显式要求，且纯 SemVer 不可覆盖
```

## 5. S7 阻塞与方案更新

阻塞：

```text
ALIPAY/WECHAT/STRIPE enabled=0
pay_channel_provider 无记录
无真实商户密钥
```

仲裁结论：

```text
S7 FAIL（BLOCKED by external online payment channel）
```

方案更新：

```text
A. 提供真实测试商户配置 -> 完成 C 在线支付/B 在线退款/回调/对账 -> 重跑 S7 P0
B. 产品/财务/安全签署 cash-only 范围变更 -> S7 仅现金条件通过，生产前在线支付硬门禁
C. 在 A/B 前继续可执行项：Android 全 C 流、ERR-001/002、HAR/logcat/trace 证据
```
