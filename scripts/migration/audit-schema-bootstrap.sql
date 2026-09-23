-- 审计独立 schema 建库与授权（方案 docs/renovation/AUDIT_STORAGE_01_SERVICE.md §5 批次 2c）。
--
-- 本脚本是**发布前置步骤**，必须早于「把 common-audit-service 切到 open_audit」的那次发布：
--   * 库不存在 → 服务启动时 Flyway/连接直接失败；
--   * 顺序颠倒 → Flyway 会在 open_im 里建出分区表（与旧表同名，迁移会失败或写错库）。
--
-- 各环境怎么用（**本地统一用 Kind，不用 Compose**）：
--   * Kind（k8s/local）：**不需要本脚本**。`k8s/local/mysql-saas-init-job.yaml` 已在建库阶段
--     一并创建 open_audit 并授权（该 Job 可重复执行），所以 Kind 环境开箱即用。
--   * ACK：走运维受控步骤（方案 §8-4 决策 B，不新增 k8s 资源）——把下面两处 __DB_USERNAME__
--     换成 ACK 的实际库用户（`im_user`）执行一次即可；第二条 GRANT 在 ACK 上可跳过
--     （ACK 的 im_user 对 open_im.* 已是全量授权）。
--   * 其它环境/原生 MySQL：同样手工执行一次。
--
-- 授权口径：
--   * open_audit.*：审计服务自己的库，全量权限（Flyway 建表、保留任务读分区元数据都需要）；
--   * open_im.tnt_tenant：审计列表要回填租户名称（既有实现直接读这张表），只给只读；
--   * open_im.saa_admin_account：审计列表/详情要按 operator_id 回填操作人姓名与登录名，只给只读。
--
-- 用法（可重复执行）：
--   mysql -h <host> -uroot -p < scripts/migration/audit-schema-bootstrap.sql

CREATE DATABASE IF NOT EXISTS `open_audit`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

GRANT ALL PRIVILEGES ON `open_audit`.* TO '__DB_USERNAME__'@'%';
-- 审计列表按租户ID回填租户名：跨 schema 只读，不扩大既有跨域读取范围。
GRANT SELECT ON `open_im`.`tnt_tenant` TO '__DB_USERNAME__'@'%';
-- 审计列表/详情按 operator_id 回填操作人姓名与登录名（关联键是 saa_admin_account.platform_account_id）。
-- 同样是跨 schema 只读，只读这两张表，不新开其它读权限。
GRANT SELECT ON `open_im`.`saa_admin_account` TO '__DB_USERNAME__'@'%';

FLUSH PRIVILEGES;
