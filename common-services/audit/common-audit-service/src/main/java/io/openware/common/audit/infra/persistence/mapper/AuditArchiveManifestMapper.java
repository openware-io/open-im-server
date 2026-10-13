package io.openware.common.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.common.audit.infra.persistence.po.AuditArchiveManifestPo;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditArchiveManifestMapper extends BaseMapper<AuditArchiveManifestPo> {

    /**
     * 幂等登记清单行：同一 {@code (period, partition_name)} 重复执行不再插入。
     *
     * <p>用 {@code INSERT ... SELECT ... WHERE NOT EXISTS} 而不是 {@code INSERT IGNORE}
     * （H2 不支持、且会吞掉 NOT NULL 等真实错误），也不用 {@code ON DUPLICATE KEY UPDATE}
     * （affected-rows 语义在 MySQL 与 H2 上不一致）——归档任务按小时运行，必须能反复安全执行。
     *
     * <p>DC-11 的四个审计列显式列出，不吃列默认值：写入方只有审计服务自身（归档任务，系统），
     * {@code created_by} / {@code updated_by} = {@code 0}；执行归档的人已经记在业务列
     * {@code operator_id} 上（可为空，不能直接拿来填 NOT NULL 的审计列）。清单只增不改，
     * {@code updated_at} 与 {@code created_at} 取同一个入参，避免将来默认值变化静默改变审计语义。
     *
     * @return 插入成功 1；已存在 0
     */
    @Insert("INSERT INTO iam_audit_archive_manifest "
            + "(period, partition_name, object_path, row_count, checksum_sha256, state, operator_id, "
            + "created_by, created_at, updated_by, updated_at) "
            + "SELECT #{period}, #{partitionName}, NULL, #{rowCount}, NULL, #{state}, #{operatorId}, "
            + "0, #{createdAt}, 0, #{createdAt} "
            + "FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM iam_audit_archive_manifest "
            + "WHERE period = #{period} AND partition_name = #{partitionName})")
    int insertIfAbsent(@Param("period") String period, @Param("partitionName") String partitionName,
                       @Param("rowCount") long rowCount, @Param("state") String state,
                       @Param("operatorId") Long operatorId, @Param("createdAt") LocalDateTime createdAt);
}
