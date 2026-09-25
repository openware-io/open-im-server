# 客户端发版 SOP（2.0.x）

> 目的：把 App（Android APK）/ Windows 桌面端（exe）的正式发版流程固化，后续按本文直接执行。

## 0. 规范（务必遵守）

- **小版本号 patch 累加**：2.0.0 → 2.0.1 → 2.0.2 …；App build 号同步 +82 → +83 → +84。
- **APK 必须用正式密钥签名**：`android/key.properties`（`xchpro.jks` + 口令）驱动 release 签名；该文件与 `android/*.jks` 已被 `.gitignore` 排除，不得入库。缺 `key.properties` 会退化为 debug 签名，禁止发布该产物。打包后验签：`apksigner verify --print-certs` 的证书 SHA-256 应为 `27cdc427d6152d0db97b78819d1ac5ba732300e5cc48bdb8820a9ce909fee10d`。
- **独立版本模型**：SaaS 平台统一 2.0.x；IM 域 + gateway 各自独立 1.x，不并入 2.0.x。
- **发版记录走 adm_client_release + adm_client_release_artifact**（im_server 库），状态 released、rollout 100。

## 1. 版本累加（代码）

- Maven：根/聚合器/sdk 2.0.0→2.0.1；im-user 1.0.28→1.0.29；gateway 1.1.0→1.1.1（勿动 im-admin 等自身版本）。
- App：open-chat-app/pubspec.yaml  version: 2.0.0+82 → 2.0.1+83。
- 桌面：open-chat-desktop/package.json  version 2.0.0 → 2.0.1；后台 open-saas-admin/package.json 同。

## 2. 构建产物

- **App 必须带 prod 环境**：`tools\build.ps1 android prod`（或 `flutter build apk --release --dart-define=APP_ENV=prod`），否则 App 默认 dev 环境 base URL=http://192.168.1.3:3002（本地），用户设备连不上报网络错误。产物 build/app/outputs/flutter-apk/app-release.apk（APK 不签名）。
- npm run build:win  →  dist/WV Chat Setup <ver>.exe。

## 3. 上传制品到 MinIO（im-business-private bucket）

关键：kubectl cp 在 Windows 下把 D 盘的冒号误判为 pod 分隔符，必须用 docker mc + port-forward。

- kubectl port-forward svc/minio 9000:9000 -n im-business（后台）。
- docker run --rm --entrypoint sh -v <apk目录>:/apk -v <exe目录>:/exe minio/mc:latest -c 执行 mc alias set local http://host.docker.internal:9000 imroot <MINIO_ROOT_PASSWORD>，再 mc cp 到 local/im-business-private/release/{android|windows}/<objectKey>。
- 下载 URL = https://api.dev.example.com/im-business-private/release/{android|windows}/<objectKey>。

## 4. 建 release 记录（im_server 库）

- build_number 递增：android 82→83、windows 2→3（查 max(build_number) per platform/channel）。
- 注意：adm_client_release 有唯一键 (platform,channel,version) + updated_at 无默认值。同版本发新版（如 2.0.1 的 build 83→84）用 UPDATE 现有 release 的 build_number/updated_at + UPDATE artifact 的 download_url/sha256/size_bytes/updated_at；只有版本号变化才 INSERT 新 release。
- INSERT INTO adm_client_release (...,platform,channel,version,build_number,status,rollout_percent,rollout_salt,release_notes,compatibility_json,created_at,updated_at,...) 值为 released、rollout 100、JSON_OBJECT('protocolVersion','v1','minimumServerCapabilityVersion',1)。
- INSERT INTO adm_client_release_artifact (release_id,architecture,package_type,download_url,sha256,size_bytes,created_at,updated_at,...) 值 apk=universal / exe=x64。

## 5. 验证

- Invoke-RestMethod https://api.dev.example.com/api/v1/client/releases/latest?platform=android&channel=stable（应返回新 version + 正确 downloadUrl/sha256/sizeBytes）。

## 6. 打包发版踩坑记录（必记，避免再犯）

1. **App 必须 APP_ENV=prod**：缺 `--dart-define=APP_ENV=prod` 时 App 默认 dev 环境，base URL 指向本地 `http://192.168.1.3:3002`，用户 Android 设备连不上报「网络错误」（浏览器却正常）。用 `tools\build.ps1 android prod`。
2. **TLS 根证书信任**：`api.dev.example.com` 的证书链根是 `Sectigo Public Server Authentication Root R46`（较新根），部分旧 Android 系统信任库未收录 → SSL 握手失败报「网络错误」。已在 App 内置 `android/app/src/main/res/raw/sectigo_r46.pem` + `res/xml/network_security_config.xml`（trust-anchors = system + 该根）兜底。换证书/域名后必须同步验证 App 是否信任新根。
3. **release 唯一键 (platform,channel,version)**：同版本发新 build（如 2.0.1 的 83→84）只能 UPDATE 现有 release 的 build_number + UPDATE artifact 的 download_url/sha256/size_bytes，不能 INSERT（会撞唯一键）；只有版本号变化才 INSERT 新 release。`updated_at` 无默认值必须显式给值。
4. **APK 必须签名**：`android/key.properties` + `xchpro.jks` 驱动 release 签名（原「暂不签名」短期例外已于 2026-09-10 取消）。缺 `key.properties` 时 Gradle 会静默退化为 debug 签名，务必用 `apksigner verify --print-certs` 核对证书 SHA-256 = `27cdc427d6152d0db97b78819d1ac5ba732300e5cc48bdb8820a9ce909fee10d` 后再发布。
5. **上传制品用 docker mc**：kubectl cp 在 Windows 会把 D 盘冒号误判为 pod 分隔符，必须 docker minio/mc + port-forward（且 mc 镜像 ENTRYPOINT 是 mc，要 `--entrypoint sh`）。
6. **E2E 前先清 port-forward 僵尸**：Windows 下 `kubectl port-forward` 子进程在 `job_kill` 后可能残留为僵尸进程仍占用端口（如 30002），把请求导到错误目标导致 E2E 全 FAIL。跑 E2E 前必须 `Get-NetTCPConnection -LocalPort <端口>` 确认端口归属，发现非 kubectl 的残留进程用 `Stop-Process -Id <PID> -Force` 清掉再重开。

## 附录：凭据来源（k8s secret gv-im-secret，base64）

- MySQL root：MYSQL_ROOT_PASSWORD；MinIO：MINIO_ROOT_USER/MINIO_ROOT_PASSWORD；bucket=MEDIA_PRIVATE_BUCKET；公网前缀=MEDIA_PUBLIC_BASE_URL。
- 发版管理员入口：https://admin.dev.example.com（账号 admin）。
