package com.gvchat.common.audit.infra.persistence.repository;

import com.gvchat.common.audit.domain.repository.AuditRetentionPort;
import com.gvchat.common.audit.infra.persistence.mapper.AuditPartitionMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 保留策略副作用出口的 MySQL 实现：分区维护 + 幂等台账清理（见端口注释与迁移脚本说明）。 */
@Repository
public class AuditRetentionPortImpl implements AuditRetentionPort {

    private final AuditPartitionMapper mapper;

    public AuditRetentionPortImpl(AuditPartitionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<String> partitionNames() {
        List<String> names = mapper.partitionNames();
        return names == null ? List.of() : names;
    }

    @Override
    public void addMonthPartition(String partitionName, String upperBoundExclusive) {
        mapper.reorganizePmaxInto(partitionName, upperBoundExclusive);
    }

    @Override
    public long rowsInPartition(String partitionName) {
        return mapper.countInPartition(partitionName);
    }

    @Override
    public int deleteIdempotencyBefore(LocalDateTime cutoff) {
        return mapper.deleteIdempotencyBefore(cutoff);
    }
}
