-- 多时区改造批 1 地基：门店时区与营业日切点落地
--   依据：docs/renovation/MULTI_TIMEZONE_DESIGN.md §0.2 决策点 2/3、§2.1、§3.1、§4.1 D1
--
-- 背景：tnt_store.timezone / tnt_store.business_day_cutoff 自 V1 建表就存在，但全仓没有任何一行代码读写它们
-- （文档 §1.1(5)）。批 1 先让这两列「有值且不可为空」，为后续批次的换算提供确定输入。
--
-- 【前置探针结论（文档 §1.4，决定存量口径是否要改写）】
-- 2026-09-18 在 kind 集群 gv-im-local 对 6 个 Deployment 实测（platform-tenant-service、platform-order-service、
-- common-payment-service、im-message-service、platform-resource-service、common-audit-service）：
--   kubectl -n gv-im-local exec deploy/<svc> -- java -XshowSettings:properties -version 2>&1 |
--     Select-String -Pattern "user.timezone|user.country|file.encoding"
--   => file.encoding = UTF-8、user.country = US、java.version = 25.0.4，**输出里没有 user.timezone 行**（属性未设置）
--   kubectl -n gv-im-local exec deploy/<svc> -- date
--   => Fri Sep 18 00:07:29 UTC 2026；/etc/localtime 不存在；容器 env 无 TZ（Dockerfile 与 k8s 清单都没注入）
-- 结论：**当前容器 JVM 默认时区 = UTC（GMT，偏移 +00:00）**。
--   => 文档 §5.1 的「乙类」存量列（LocalDateTime.now() 写入）本身就是 UTC 字面量，**本批不做任何存量时间换算**，
--      存量解释与改造前一致（批 1 的 k8s/Dockerfile 改动只是把这一既成事实显式化，见文档 §5.1 类别乙）。
--   => 本迁移只处理「门店配置」两列，不触碰任何业务时间列。
--
-- 【回填公式】
--   timezone            = COALESCE(NULLIF(TRIM(store.timezone),''), NULLIF(TRIM(tenant.default_timezone),''), 'Asia/Shanghai')
--   business_day_cutoff = COALESCE(store.business_day_cutoff, '04:00:00')
--   缺省链与文档 §2.1 一致：门店 → 租户 default_timezone → 平台默认 Asia/Shanghai。
--
-- 【为什么两列都加 NOT NULL（文档 §4.1 D1 / §3.1 / §2.1 决策点 2）】
--   1) D1 明确要求 timezone 加 NOT NULL；§3.1 明确要求切点「必须显式配置而不是留 NULL」，
--      留 NULL 会让「营业日」退化为自然日。
--   2) 加 NOT NULL 的前提是历史行已全部回填：本文件的两条 UPDATE 在同一迁移内先于 ALTER 执行，
--      覆盖所有 NULL 与空串行（门店 0 行时 UPDATE 影响 0 行，ALTER 仍成功）。
--   3) 平台默认值同时作为列 DEFAULT 落库：门店创建入口尚未落地（批 1 只加修改接口），
--      新建门店若漏传时区，宁可落到显式的平台默认值，也不要让写入直接失败后由调用方各自兜底。
--      「取租户 default_timezone 预填」的应用层规则由后续批次在门店创建接口实现，DB 默认值只是最后一层。
--   4) 业务时间列一律不动：仍是 datetime(3)，**禁止**改成 timestamp（MySQL TIMESTAMP 会按会话时区隐式转换，文档 §2.2）。
--
-- 【本迁移不做的事】
--   * 不新增影子列 / business_date / store_timezone 物化列（文档 §4.1 D2~D4 属批 2）；
--   * 不换算任何存量业务时间（批 3）；
--   * 不加 IANA 格式的 CHECK 约束：DB 不判断时区合法性，由 StoreTimeService 在应用层校验（文档 §4.1 D1）。
--
-- 编号：tenant 模块截至本提交的最大版本为 V26（V26__seed_currency_permission.sql），故取 V27。

-- 1) 回填门店时区：门店 → 租户默认 → 平台默认 Asia/Shanghai
UPDATE tnt_store s
LEFT JOIN tnt_tenant t ON t.id = s.tenant_id
SET s.timezone = COALESCE(NULLIF(TRIM(s.timezone), ''), NULLIF(TRIM(t.default_timezone), ''), 'Asia/Shanghai')
WHERE s.timezone IS NULL OR TRIM(s.timezone) = '';

-- 2) 回填营业日切点：缺省 04:00:00（KTV 通宵场次归前一营业日，文档 §3.1）
UPDATE tnt_store SET business_day_cutoff = '04:00:00' WHERE business_day_cutoff IS NULL;

-- 3) 加非空约束与平台默认值（此时历史行已全部有值）
ALTER TABLE `tnt_store`
  MODIFY COLUMN `timezone` varchar(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA时区（门店级权威来源）',
  MODIFY COLUMN `business_day_cutoff` time NOT NULL DEFAULT '04:00:00' COMMENT '营业日切点（00:00-12:00，默认04:00）';
