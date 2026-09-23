package io.openware.common.audit.domain.repository;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/**
 * 冷归档清单仓储：记录「哪些月份已归档 / 被法律保留」，并回答「已归档月份集合」。
 *
 * <p>只做清单登记与查询，不做导入导出：本阶段（方案 §5 批次 4）不投递对象存储、不删分区，
 * 投递与删除落地时也只允许在**清单存在且校验通过**的前提下进行。
 */
public interface AuditArchiveManifestRepository {

    /**
     * 登记某月已归档（幂等）：同一个月重复执行只保留一条。
     *
     * @param period       归档月份 {@code YYYY-MM}
     * @param rowCount     归档时的行数快照（本阶段作为清单证据；投递落地后与校验和一起复核）
     */
    void markArchived(YearMonth period, String partitionName, long rowCount, Long operatorId);

    /** 登记某月被法律保留（幂等）：只留痕，不改动数据、不影响可查范围。 */
    void markHeld(YearMonth period, String partitionName, Long operatorId);

    /** 已归档（{@code ARCHIVED}）的月份集合：列表可查下限由此推导。 */
    Set<YearMonth> archivedMonths();

    /** 全部清单记录（按月份倒序），供运维巡检与后续投递任务使用。 */
    List<AuditArchiveManifestView> findAll();

    /** 清单记录视图（供巡检/后续投递任务读取，避免直接暴露 PO）。 */
    record AuditArchiveManifestView(YearMonth period, String partitionName, long rowCount, String state) { }
}
