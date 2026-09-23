package io.openware.common.audit.api.dto;

/** 单条审计写入回执：{@code duplicated=true} 表示命中幂等键，未新增记录。 */
public record AuditWriteResult(long id, boolean duplicated) {
}
