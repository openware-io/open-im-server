package com.gvchat.common.audit.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.audit.domain.model.AuditLog;
import com.gvchat.common.audit.domain.repository.AuditLogRepository;
import com.gvchat.common.audit.infra.persistence.mapper.IamAuditLogMapper;
import com.gvchat.common.audit.infra.persistence.mapper.OperatorNameMapper;
import com.gvchat.common.audit.infra.persistence.mapper.TenantNameMapper;
import com.gvchat.common.audit.infra.persistence.po.IamAuditLogPo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 「真实多行批插」的行为契约（Mockito 断言语句级行为，H2 用例只覆盖语义）。
 *
 * <p>背景：批量上报原本是**逐条单行 INSERT**（200 条 = 200 次往返）。审计主表是高频写入大表，
 * 批量路径的写入成本几乎全在这里。改造后的口径是：
 * <ul>
 *   <li>台账（小表）仍逐条抢占——需要逐条的「首次/重复」判定，且写入前就已确定 audit_id（无需回填）；</li>
 *   <li>审计主表（大表）**一次多行 INSERT**，不再逐条往返；</li>
 *   <li>超过单批上限时按 {@code MAX_INSERT_CHUNK} 分片，不产生超长 SQL。</li>
 * </ul>
 * 这里直接数 mapper 调用次数，确保以后有人改回「循环单插」时用例立刻失败。
 */
class AuditBatchInsertTest {

    private static final long TENANT_ID = 1001L;

    private IamAuditLogMapper mapper;
    private AuditLogRepository repository;

    @BeforeEach
    void setUp() {
        mapper = mock(IamAuditLogMapper.class);
        repository = new AuditLogRepositoryImpl(mapper, mock(TenantNameMapper.class),
        mock(OperatorNameMapper.class));
        // 默认：所有幂等键都抢得到（首次上报）。
        when(mapper.claimIdempotencyKey(anyLong(), anyString(), anyLong(), any())).thenReturn(1);
    }

    @Test
    void batchUsesSingleMultiRowInsertInsteadOfPerRowInserts() {
        List<AuditLog> entries = entries(3);

        List<AuditLogRepository.SaveResult> results = repository.saveAllIfAbsent(entries);

        assertEquals(3, results.size());
        assertTrue(results.stream().noneMatch(AuditLogRepository.SaveResult::duplicated));

        ArgumentCaptor<List<IamAuditLogPo>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper, times(1)).insertBatch(captor.capture());
        assertEquals(3, captor.getValue().size(), "三条记录必须在同一条多行 INSERT 里");
        verify(mapper, never()).insert(any(IamAuditLogPo.class));
        verify(mapper, times(3)).claimIdempotencyKey(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void batchIsChunkedSoSingleStatementStaysBounded() {
        List<AuditLog> entries = entries(AuditLogRepositoryImpl.MAX_INSERT_CHUNK + 1);

        repository.saveAllIfAbsent(entries);

        ArgumentCaptor<List<IamAuditLogPo>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper, times(2)).insertBatch(captor.capture());
        assertEquals(List.of(AuditLogRepositoryImpl.MAX_INSERT_CHUNK, 1),
                captor.getAllValues().stream().map(List::size).toList(),
                "超过单批上限必须分片，避免超长 SQL");
    }

    @Test
    void duplicateEntriesAreNotInsertedAgain() {
        when(mapper.claimIdempotencyKey(anyLong(), anyString(), anyLong(), any()))
                .thenReturn(0);
        when(mapper.findClaimedAuditId(eq(TENANT_ID), anyString())).thenReturn(4242L);

        List<AuditLogRepository.SaveResult> results = repository.saveAllIfAbsent(entries(2));

        assertTrue(results.stream().allMatch(AuditLogRepository.SaveResult::duplicated));
        assertEquals(4242L, results.get(0).id(), "重复项要回执首次落库的 ID");
        verify(mapper, never()).insertBatch(any());
        verify(mapper, never()).insert(any(IamAuditLogPo.class));
    }

    /** 主键缺失属于编程错误：必须立刻失败，而不是让库里的自增值与台账记录不一致。 */
    @Test
    void entryWithoutApplicationGeneratedIdFailsFast() {
        List<AuditLog> entries = entries(1);
        entries.get(0).setId(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.saveAllIfAbsent(entries));

        assertTrue(error.getMessage().contains("主键"));
        verify(mapper, never()).insertBatch(any());
    }

    /** 单条路径同样只写一次单行 INSERT，且台账在抢占时就带上 audit_id（不再有回填 UPDATE）。 */
    @Test
    void singleWriteClaimsWithAuditIdAndInsertsOnce() {
        AuditLog entry = entries(1).get(0);

        AuditLogRepository.SaveResult result = repository.saveIfAbsent(entry);

        assertFalse(result.duplicated());
        assertEquals(entry.getId(), result.id());
        verify(mapper).claimIdempotencyKey(TENANT_ID, entry.getIdempotencyKey(), entry.getId(), entry.getCreatedAt());
        verify(mapper, times(1)).insert(any(IamAuditLogPo.class));
        verify(mapper, never()).attachAuditIdIfAbsent(anyLong(), anyString(), anyLong());
    }

    /** 台账有键无记录（历史残留）时补写主表并回填 ID，否则每次重试都会再插一条。 */
    @Test
    void ledgerEntryWithoutAuditIdIsBackfilledAfterRecoveryInsert() {
        when(mapper.claimIdempotencyKey(anyLong(), anyString(), anyLong(), any())).thenReturn(0);
        when(mapper.findClaimedAuditId(eq(TENANT_ID), anyString())).thenReturn(null);
        AuditLog entry = entries(1).get(0);

        AuditLogRepository.SaveResult result = repository.saveIfAbsent(entry);

        assertFalse(result.duplicated(), "台账无记录 ID 时按未写入补写，绝不静默丢审计");
        verify(mapper, times(1)).insert(any(IamAuditLogPo.class));
        verify(mapper, times(1)).attachAuditIdIfAbsent(TENANT_ID, entry.getIdempotencyKey(), entry.getId());
    }

    private static List<AuditLog> entries(int count) {
        List<AuditLog> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            AuditLog log = new AuditLog();
            LocalDateTime now = LocalDateTime.of(2026, 9, 19, 12, 0);
            log.setId(900_000L + index);
            log.setTenantId(TENANT_ID);
            log.setAction("order.settle");
            log.setIdempotencyKey("batch-" + index);
            log.setResult(AuditLog.RESULT_SUCCESS);
            log.setCreatedAt(now);
            log.setOccurredAt(now);
            entries.add(log);
        }
        return entries;
    }
}
