package com.gvchat.common.audit.infra.id;

import com.gvchat.common.util.SnowflakeIdGenerator;
import org.springframework.stereotype.Component;

/**
 * 审计记录主键生成器：**应用侧生成 ID**，不再依赖数据库自增。
 *
 * <p>为什么必须由应用侧生成（方案 docs/renovation/AUDIT_STORAGE_01_SERVICE.md §5 批次 2b）：
 * <ul>
 *   <li>批量上报要压成一条多行 {@code INSERT ... VALUES (),(),...}，插入后无法逐条回读主键；
 *       而接口契约要逐条回执 ID，台账也要在抢占时就写下记录 ID，所以插入前就得知道主键；</li>
 *   <li>顺带消掉 {@code AUTO_INCREMENT} 与「按 occurred_at 月分区」组合下的取号/回填边界问题。</li>
 * </ul>
 *
 * <p>复用 {@code sdk/common} 的 {@link SnowflakeIdGenerator}（雪花算法，仓内既有标准），
 * 不新增依赖；其 {@code nextId()} 返回无符号十进制字符串，这里转成 {@code long}
 * 以匹配 {@code bigint unsigned} 主键（按位等价，不丢信息）。
 */
@Component
public class AuditIdGenerator {

    private final SnowflakeIdGenerator snowflake;

    public AuditIdGenerator(SnowflakeIdGenerator snowflake) {
        this.snowflake = snowflake;
    }

    /** 生成一个全局唯一、趋势递增的审计记录 ID。 */
    public long nextId() {
        return Long.parseUnsignedLong(snowflake.nextId());
    }
}
