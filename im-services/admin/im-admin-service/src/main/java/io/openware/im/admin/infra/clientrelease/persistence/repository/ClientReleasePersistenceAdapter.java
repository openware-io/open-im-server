package io.openware.im.admin.infra.clientrelease.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.im.admin.domain.clientrelease.model.*;
import io.openware.im.admin.domain.clientrelease.repository.*;
import io.openware.im.admin.infra.clientrelease.persistence.mapper.*;
import io.openware.im.admin.infra.clientrelease.persistence.po.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** Persistence adapter for release aggregates, immutable audits, and idempotent operations. */
@Repository
@RequiredArgsConstructor
public class ClientReleasePersistenceAdapter implements ClientReleaseRepository,
    ClientReleasePolicyRepository, ClientReleaseAuditRepository, ClientReleaseOperationRepository {
  private final ClientReleaseMapper releaseMapper;
  private final ClientReleaseArtifactMapper artifactMapper;
  private final ClientReleasePolicyMapper policyMapper;
  private final ClientReleaseAuditLogMapper auditLogMapper;
  private final ClientReleaseOperationMapper operationMapper;
  private final ObjectMapper objectMapper;

  @Override public Optional<ClientRelease> findById(Long id) { return Optional.ofNullable(releaseMapper.selectById(id)).map(this::release); }
  @Override public List<ClientRelease> findAll(ReleasePlatform platform, ReleaseChannel channel, ReleaseStatus status, int offset, int size) {
    return releaseMapper.selectList(Wrappers.<ClientReleasePo>lambdaQuery().eq(platform != null, ClientReleasePo::getPlatform, platform).eq(channel != null, ClientReleasePo::getChannel, channel).eq(status != null, ClientReleasePo::getStatus, status).orderByDesc(ClientReleasePo::getId).last("LIMIT " + offset + "," + size)).stream().map(this::release).toList();
  }
  @Override public long count(ReleasePlatform platform, ReleaseChannel channel, ReleaseStatus status) {
    return releaseMapper.selectCount(Wrappers.<ClientReleasePo>lambdaQuery().eq(platform != null, ClientReleasePo::getPlatform, platform).eq(channel != null, ClientReleasePo::getChannel, channel).eq(status != null, ClientReleasePo::getStatus, status));
  }
  @Override public List<ClientRelease> findEligible(ReleasePlatform platform, ReleaseChannel channel, TargetArchitecture architecture) {
    return releaseMapper.selectList(Wrappers.<ClientReleasePo>lambdaQuery().eq(ClientReleasePo::getPlatform, platform).eq(ClientReleasePo::getChannel, channel).in(ClientReleasePo::getStatus, List.of(ReleaseStatus.RELEASED, ReleaseStatus.ROLLING_OUT)).orderByDesc(ClientReleasePo::getBuildNumber)).stream().map(this::release).filter(value -> value.artifacts().stream().anyMatch(artifact -> artifact.architecture() == architecture || artifact.architecture() == TargetArchitecture.UNIVERSAL)).toList();
  }
  @Override public List<ClientRelease> findScheduledDue(LocalDateTime now, int size) {
    return releaseMapper.selectList(Wrappers.<ClientReleasePo>lambdaQuery().eq(ClientReleasePo::getStatus, ReleaseStatus.SCHEDULED).le(ClientReleasePo::getScheduledAt, now).orderByAsc(ClientReleasePo::getScheduledAt).last("LIMIT " + size)).stream().map(this::release).toList();
  }
  @Override public long maxBuildNumber(ReleasePlatform platform, ReleaseChannel channel) {
    Long maxBuildNumber = releaseMapper.selectObjs(Wrappers.<ClientReleasePo>lambdaQuery().select(ClientReleasePo::getBuildNumber).eq(ClientReleasePo::getPlatform, platform).eq(ClientReleasePo::getChannel, channel).orderByDesc(ClientReleasePo::getBuildNumber).last("LIMIT 1")).stream().findFirst().map(value -> ((Number) value).longValue()).orElse(0L);
    return maxBuildNumber;
  }
  @Override public ClientRelease save(ClientRelease value) {
    ClientReleasePo po = releasePo(value); releaseMapper.insert(po);
    value.artifacts().forEach(artifact -> artifactMapper.insert(artifactPo(artifact, po.getId(), value.createdBy(), value.createdAt())));
    return findById(po.getId()).orElseThrow();
  }
  @Override public boolean update(ClientRelease value, long expectedRowVersion) {
    ClientReleasePo po = releasePo(value); po.setRowVersion(expectedRowVersion + 1);
    return releaseMapper.update(po, Wrappers.<ClientReleasePo>lambdaUpdate().eq(ClientReleasePo::getId, value.id()).eq(ClientReleasePo::getRowVersion, expectedRowVersion)) == 1;
  }
  @Override public void replaceArtifacts(long releaseId, List<ReleaseArtifact> artifacts, long actorId, LocalDateTime timestamp) {
    artifactMapper.delete(Wrappers.<ClientReleaseArtifactPo>lambdaQuery().eq(ClientReleaseArtifactPo::getReleaseId, releaseId));
    artifacts.forEach(artifact -> artifactMapper.insert(artifactPo(artifact, releaseId, actorId, timestamp)));
  }
  @Override public Optional<ClientReleasePolicy> find(ReleasePlatform platform, ReleaseChannel channel) {
    return Optional.ofNullable(policyMapper.selectOne(Wrappers.<ClientReleasePolicyPo>lambdaQuery().eq(ClientReleasePolicyPo::getPlatform, platform).eq(ClientReleasePolicyPo::getChannel, channel))).map(this::policy);
  }
  @Override public List<ClientReleasePolicy> findAll() { return policyMapper.selectList(Wrappers.<ClientReleasePolicyPo>lambdaQuery().orderByAsc(ClientReleasePolicyPo::getPlatform).orderByAsc(ClientReleasePolicyPo::getChannel)).stream().map(this::policy).toList(); }
  @Override public ClientReleasePolicy save(ClientReleasePolicy value) { ClientReleasePolicyPo po = policyPo(value); policyMapper.insert(po); return policy(po); }
  @Override public boolean update(ClientReleasePolicy value, long expectedRowVersion) {
    ClientReleasePolicyPo po = policyPo(value); po.setRowVersion(expectedRowVersion + 1);
    return policyMapper.update(po, Wrappers.<ClientReleasePolicyPo>lambdaUpdate().eq(ClientReleasePolicyPo::getId, value.id()).eq(ClientReleasePolicyPo::getRowVersion, expectedRowVersion)) == 1;
  }
  @Override public ReleaseAuditLog append(ReleaseAuditLog value) {
    ClientReleaseAuditLogPo po = new ClientReleaseAuditLogPo(); po.setReleaseId(value.releaseId()); po.setPolicyId(value.policyId()); po.setAction(value.action()); po.setBeforeStatus(value.beforeStatus()); po.setAfterStatus(value.afterStatus()); po.setRequestId(value.requestId()); po.setIdempotencyKey(value.idempotencyKey()); po.setReason(value.reason()); po.setPayloadDigest(value.payloadDigest()); po.setCreatedBy(value.createdBy()); po.setCreatedAt(value.createdAt()); po.setUpdatedBy(value.createdBy()); po.setUpdatedAt(value.createdAt()); auditLogMapper.insert(po); return audit(po);
  }
  @Override public List<ReleaseAuditLog> findByReleaseId(long releaseId, int offset, int size) {
    return auditLogMapper.selectList(Wrappers.<ClientReleaseAuditLogPo>lambdaQuery().eq(ClientReleaseAuditLogPo::getReleaseId, releaseId).orderByDesc(ClientReleaseAuditLogPo::getCreatedAt).last("LIMIT " + offset + "," + size)).stream().map(this::audit).toList();
  }
  @Override public long countByReleaseId(long releaseId) { return auditLogMapper.selectCount(Wrappers.<ClientReleaseAuditLogPo>lambdaQuery().eq(ClientReleaseAuditLogPo::getReleaseId, releaseId)); }
  @Override public Optional<ClientReleaseOperation> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(operationMapper.selectOne(Wrappers.<ClientReleaseOperationPo>lambdaQuery().eq(ClientReleaseOperationPo::getIdempotencyKey, idempotencyKey))).map(this::operation);
  }
  @Override public ClientReleaseOperation save(ClientReleaseOperation value) {
    ClientReleaseOperationPo po = new ClientReleaseOperationPo(); po.setIdempotencyKey(value.idempotencyKey()); po.setAction(value.action()); po.setRequestDigest(value.requestDigest()); po.setReleaseId(value.releaseId()); po.setPolicyId(value.policyId()); po.setCreatedBy(value.createdBy()); po.setCreatedAt(value.createdAt()); po.setUpdatedBy(value.createdBy()); po.setUpdatedAt(value.createdAt()); operationMapper.insert(po); return operation(po);
  }
  private ClientRelease release(ClientReleasePo po) {
    return new ClientRelease(po.getId(), po.getPlatform(), po.getChannel(), po.getVersion(), po.getBuildNumber(), po.getStatus(), Boolean.TRUE.equals(po.getMandatory()), po.getRolloutPercent(), po.getRolloutSalt(), po.getScheduledAt(), po.getPublishedAt(), po.getReleaseNotes(), po.getStoreUrl(), compatibility(po.getCompatibilityJson()), po.getRowVersion(), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt(), artifactMapper.selectList(Wrappers.<ClientReleaseArtifactPo>lambdaQuery().eq(ClientReleaseArtifactPo::getReleaseId, po.getId())).stream().map(this::artifact).toList());
  }
  private ClientReleasePo releasePo(ClientRelease value) {
    ClientReleasePo po = new ClientReleasePo(); po.setId(value.id()); po.setPlatform(value.platform()); po.setChannel(value.channel()); po.setVersion(value.version()); po.setBuildNumber(value.buildNumber()); po.setStatus(value.status()); po.setMandatory(value.mandatory()); po.setRolloutPercent(value.rolloutPercent()); po.setRolloutSalt(value.rolloutSalt()); po.setScheduledAt(value.scheduledAt()); po.setPublishedAt(value.publishedAt()); po.setReleaseNotes(value.releaseNotes()); po.setStoreUrl(value.storeUrl()); po.setCompatibilityJson(json(value.compatibility())); po.setRowVersion(value.rowVersion()); po.setCreatedBy(value.createdBy()); po.setCreatedAt(value.createdAt()); po.setUpdatedBy(value.updatedBy()); po.setUpdatedAt(value.updatedAt()); return po;
  }
  private ReleaseArtifact artifact(ClientReleaseArtifactPo po) { return new ReleaseArtifact(po.getId(), po.getReleaseId(), po.getArchitecture(), po.getPackageType(), po.getDownloadUrl(), po.getSha256(), po.getSizeBytes(), po.getSigningMetadataJson()); }
  private ClientReleaseArtifactPo artifactPo(ReleaseArtifact value, Long releaseId, Long actorId, LocalDateTime timestamp) {
    ClientReleaseArtifactPo po = new ClientReleaseArtifactPo(); po.setReleaseId(releaseId); po.setArchitecture(value.architecture()); po.setPackageType(value.packageType()); po.setDownloadUrl(value.downloadUrl()); po.setSha256(value.sha256()); po.setSizeBytes(value.sizeBytes()); po.setSigningMetadataJson(value.signingMetadataJson()); po.setCreatedBy(actorId); po.setCreatedAt(timestamp); po.setUpdatedBy(actorId); po.setUpdatedAt(timestamp); return po;
  }
  private ClientReleasePolicy policy(ClientReleasePolicyPo po) { return new ClientReleasePolicy(po.getId(), po.getPlatform(), po.getChannel(), po.getMinimumBuildNumber(), po.getRowVersion(), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()); }
  private ClientReleasePolicyPo policyPo(ClientReleasePolicy value) { ClientReleasePolicyPo po = new ClientReleasePolicyPo(); po.setId(value.id()); po.setPlatform(value.platform()); po.setChannel(value.channel()); po.setMinimumBuildNumber(value.minimumBuildNumber()); po.setRowVersion(value.rowVersion()); po.setCreatedBy(value.createdBy()); po.setCreatedAt(value.createdAt()); po.setUpdatedBy(value.updatedBy()); po.setUpdatedAt(value.updatedAt()); return po; }
  private ReleaseAuditLog audit(ClientReleaseAuditLogPo po) { return new ReleaseAuditLog(po.getId(), po.getReleaseId(), po.getPolicyId(), po.getAction(), po.getBeforeStatus(), po.getAfterStatus(), po.getRequestId(), po.getIdempotencyKey(), po.getReason(), po.getPayloadDigest(), po.getCreatedBy(), po.getCreatedAt()); }
  private ClientReleaseOperation operation(ClientReleaseOperationPo po) { return new ClientReleaseOperation(po.getId(), po.getIdempotencyKey(), po.getAction(), po.getRequestDigest(), po.getReleaseId(), po.getPolicyId(), po.getCreatedBy(), po.getCreatedAt()); }
  private String json(CompatibilitySnapshot value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException exception) { throw new IllegalArgumentException("Invalid compatibility snapshot", exception); } }
  private CompatibilitySnapshot compatibility(String value) { try { return objectMapper.readValue(value, CompatibilitySnapshot.class); } catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid persisted compatibility snapshot", exception); } }
}
