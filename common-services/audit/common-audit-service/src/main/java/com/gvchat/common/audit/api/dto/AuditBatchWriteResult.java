package com.gvchat.common.audit.api.dto;

import java.util.List;

/**
 * 批量审计写入回执：{@code accepted} 为实际落库条数，{@code duplicated} 为幂等命中条数。
 *
 * <p>批量是「逐条幂等」语义：部分条目命中幂等键不影响其余条目落库；任何一条校验失败则整批 400，
 * 不做「一半写入一半失败」的模糊结果。
 */
public record AuditBatchWriteResult(int accepted, int duplicated, List<AuditWriteResult> items) {
}
