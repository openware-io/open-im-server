package io.openware.common.audit.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.audit.domain.repository.AuditArchiveManifestRepository;
import io.openware.common.audit.domain.retention.AuditArchiveManifest;
import io.openware.common.audit.infra.persistence.mapper.AuditArchiveManifestMapper;
import io.openware.common.audit.infra.persistence.po.AuditArchiveManifestPo;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Repository;

/**
 * 冷归档清单仓储实现：登记幂等（唯一键 {@code (period, partition_name)}）+ 集合查询。
 *
 * <p>幂等由「先查后插」与唯一键双重保证：任务按小时运行，同一月份重复处理只会保留一条清单行。
 */
@Repository
public class AuditArchiveManifestRepositoryImpl implements AuditArchiveManifestRepository {

    private final AuditArchiveManifestMapper mapper;

    public AuditArchiveManifestRepositoryImpl(AuditArchiveManifestMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void markArchived(YearMonth period, String partitionName, long rowCount, Long operatorId) {
        upsert(period, partitionName, rowCount, AuditArchiveManifest.STATE_ARCHIVED, operatorId);
    }

    @Override
    public void markHeld(YearMonth period, String partitionName, Long operatorId) {
        upsert(period, partitionName, 0L, AuditArchiveManifest.STATE_HELD, operatorId);
    }

    @Override
    public Set<YearMonth> archivedMonths() {
        Set<YearMonth> months = new LinkedHashSet<>();
        for (AuditArchiveManifestPo po : mapper.selectList(Wrappers.<AuditArchiveManifestPo>lambdaQuery()
                .eq(AuditArchiveManifestPo::getState, AuditArchiveManifest.STATE_ARCHIVED))) {
            YearMonth month = parse(po.getPeriod());
            if (month != null) {
                months.add(month);
            }
        }
        return months;
    }

    @Override
    public List<AuditArchiveManifestView> findAll() {
        return mapper.selectList(Wrappers.<AuditArchiveManifestPo>lambdaQuery()
                        .orderByDesc(AuditArchiveManifestPo::getPeriod)).stream()
                .map(po -> new AuditArchiveManifestView(parse(po.getPeriod()), po.getPartitionName(),
                        po.getRowCount() == null ? 0L : po.getRowCount(), po.getState()))
                .toList();
    }

    private void upsert(YearMonth period, String partitionName, long rowCount, String state, Long operatorId) {
        mapper.insertIfAbsent(period.toString(), partitionName, rowCount, state, operatorId, LocalDateTime.now());
    }

    /** 清单里的 period 由本服务写入（YYYY-MM），异常值按「无法识别」跳过而不是让整次查询失败。 */
    private static YearMonth parse(String period) {
        try {
            return period == null || period.isBlank() ? null : YearMonth.parse(period.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
