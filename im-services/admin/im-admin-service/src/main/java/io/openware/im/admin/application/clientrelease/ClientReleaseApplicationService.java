package io.openware.im.admin.application.clientrelease;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.im.admin.domain.clientrelease.model.*;
import io.openware.im.admin.domain.clientrelease.repository.*;
import io.openware.common.dto.PageResult;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClientReleaseApplicationService {
  private final ClientReleaseRepository releaseRepository;
  private final ClientReleasePolicyRepository policyRepository;
  private final ClientReleaseAuditRepository auditRepository;
  private final ClientReleaseOperationRepository operationRepository;
  private final ClientReleaseProperties properties;
  private final ReleaseArtifactValidator artifactValidator;
  private final ObjectMapper objectMapper;

  public PageResult<ClientRelease> list(ReleasePlatform platform, ReleaseChannel channel, ReleaseStatus status, Integer page, Integer pageSize) {
    int current = page == null ? 1 : Math.max(1, page); int size = pageSize == null ? 20 : Math.min(100, Math.max(1, pageSize));
    return PageResult.<ClientRelease>builder().items(releaseRepository.findAll(platform, channel, status, (current - 1) * size, size)).total(releaseRepository.count(platform, channel, status)).page(current).pageSize(size).build();
  }
  public ClientRelease get(long id) { return releaseRepository.findById(id).orElseThrow(() -> notFound("Release not found")); }
  /** 官网下载页公开接口：返回指定平台/渠道最新的已发布版本（含下载地址）。 */
  public java.util.Optional<ClientRelease> latestReleased(ReleasePlatform platform, ReleaseChannel channel) {
    List<ClientRelease> released = releaseRepository.findAll(platform, channel, ReleaseStatus.RELEASED, 0, 1);
    return released.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(released.getFirst());
  }
  public List<ClientReleasePolicy> listPolicies() { return policyRepository.findAll(); }
  public PageResult<ReleaseAuditLog> listAuditLogs(long releaseId, Integer page, Integer pageSize) {
    get(releaseId); int current = page == null ? 1 : Math.max(1, page); int size = pageSize == null ? 20 : Math.min(100, Math.max(1, pageSize));
    return PageResult.<ReleaseAuditLog>builder().items(auditRepository.findByReleaseId(releaseId, (current - 1) * size, size)).total(auditRepository.countByReleaseId(releaseId)).page(current).pageSize(size).build();
  }
  public List<ReleasePlatform> platforms() { return List.of(ReleasePlatform.values()); }
  public boolean isEnabled(ReleasePlatform platform) { return properties.getEnabledPlatforms().contains(platform); }

  @Transactional public ClientRelease create(WriteContext context, ClientRelease draft) {
    return idempotent(context, ReleaseOperationAction.CREATE, draft, () -> {
      requireEnabled(draft.platform()); validateDraft(draft); LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
      if (draft.buildNumber() <= releaseRepository.maxBuildNumber(draft.platform(), draft.channel())) throw invalid("buildNumber must be strictly greater than every existing platform and channel build");
      ClientRelease saved = releaseRepository.save(new ClientRelease(null, draft.platform(), draft.channel(), draft.version(), draft.buildNumber(), ReleaseStatus.DRAFT, draft.mandatory(), 0, UUID.randomUUID().toString(), null, null, draft.releaseNotes(), draft.storeUrl(), draft.compatibility(), 0L, context.actorId(), now, context.actorId(), now, draft.artifacts()));
      audit(context, ReleaseOperationAction.CREATE, saved.id(), null, null, saved.status()); return saved;
    });
  }
  @Transactional public ClientRelease updateDraft(WriteContext context, long id, long expectedVersion, ClientRelease draft) {
    return idempotent(context, ReleaseOperationAction.UPDATE, new Object[] {id, expectedVersion, draft}, () -> {
      ClientRelease current = get(id); requireState(current, ReleaseStatus.DRAFT); requireEnabled(current.platform());
      if (current.platform() != draft.platform() || current.channel() != draft.channel() || !Objects.equals(current.buildNumber(), draft.buildNumber()) || !Objects.equals(current.version(), draft.version())) throw invalid("platform, channel, version, and buildNumber cannot be changed after draft creation");
      validateDraft(draft); LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
      ClientRelease next = new ClientRelease(id, current.platform(), current.channel(), draft.version(), draft.buildNumber(), ReleaseStatus.DRAFT, draft.mandatory(), 0, current.rolloutSalt(), null, null, draft.releaseNotes(), draft.storeUrl(), draft.compatibility(), expectedVersion + 1, current.createdBy(), current.createdAt(), context.actorId(), now, draft.artifacts());
      if (!releaseRepository.update(next, expectedVersion)) throw rowConflict(); releaseRepository.replaceArtifacts(id, draft.artifacts(), context.actorId(), now);
      ClientRelease saved = get(id); audit(context, ReleaseOperationAction.UPDATE, id, null, current.status(), saved.status()); return saved;
    });
  }
  @Transactional public ClientRelease submit(WriteContext context, long id, long expectedVersion, int percent, LocalDateTime scheduledAt) {
    return idempotent(context, ReleaseOperationAction.SUBMIT, new Object[] {id, expectedVersion, percent, scheduledAt}, () -> {
      ClientRelease current = get(id); requireState(current, ReleaseStatus.DRAFT); requireEnabled(current.platform()); validateDraft(current);
      if (percent < 1 || percent > 100) throw invalid("initialRolloutPercent must be between 1 and 100"); if (scheduledAt != null && !scheduledAt.isAfter(LocalDateTime.now(Clock.systemUTC()))) throw invalid("scheduledAt must be in the future");
      ReleaseStatus status = scheduledAt == null ? (percent == 100 ? ReleaseStatus.RELEASED : ReleaseStatus.ROLLING_OUT) : ReleaseStatus.SCHEDULED; LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
      ClientRelease next = copy(current, status, percent, scheduledAt, status == ReleaseStatus.SCHEDULED ? null : now, context.actorId(), expectedVersion);
      if (!releaseRepository.update(next, expectedVersion)) throw rowConflict(); ClientRelease saved = get(id); audit(context, ReleaseOperationAction.SUBMIT, id, null, current.status(), saved.status()); return saved;
    });
  }
  @Transactional public ClientRelease rollout(WriteContext context, long id, long expectedVersion, int percent) {
    return idempotent(context, ReleaseOperationAction.ROLLOUT, new Object[] {id, expectedVersion, percent}, () -> {
      ClientRelease current = get(id); requireState(current, ReleaseStatus.ROLLING_OUT); if (percent <= current.rolloutPercent() || percent > 100) throw invalid("rolloutPercent must increase and be at most 100");
      ReleaseStatus status = percent == 100 ? ReleaseStatus.RELEASED : ReleaseStatus.ROLLING_OUT; ClientRelease next = copy(current, status, percent, current.scheduledAt(), current.publishedAt(), context.actorId(), expectedVersion);
      if (!releaseRepository.update(next, expectedVersion)) throw rowConflict(); ClientRelease saved = get(id); audit(context, ReleaseOperationAction.ROLLOUT, id, null, current.status(), saved.status()); return saved;
    });
  }
  @Transactional public ClientRelease pause(WriteContext context, long id, long expectedVersion) { return transition(context, id, expectedVersion, ReleaseOperationAction.PAUSE, ReleaseStatus.ROLLING_OUT, ReleaseStatus.PAUSED); }
  @Transactional public ClientRelease resume(WriteContext context, long id, long expectedVersion) { return transition(context, id, expectedVersion, ReleaseOperationAction.RESUME, ReleaseStatus.PAUSED, ReleaseStatus.ROLLING_OUT); }
  @Transactional public ClientRelease withdraw(WriteContext context, long id, long expectedVersion) {
    return idempotent(context, ReleaseOperationAction.WITHDRAW, new Object[] {id, expectedVersion}, () -> {
      ClientRelease current = get(id); if (current.status() == ReleaseStatus.WITHDRAWN || current.status() == ReleaseStatus.ARCHIVED) throw invalidState("Release cannot be withdrawn");
      ClientRelease next = copy(current, ReleaseStatus.WITHDRAWN, current.rolloutPercent(), current.scheduledAt(), current.publishedAt(), context.actorId(), expectedVersion);
      if (!releaseRepository.update(next, expectedVersion)) throw rowConflict(); ClientRelease saved = get(id); audit(context, ReleaseOperationAction.WITHDRAW, id, null, current.status(), saved.status()); return saved;
    });
  }
  @Transactional public ClientRelease archive(WriteContext context, long id, long expectedVersion) {
    return idempotent(context, ReleaseOperationAction.ARCHIVE, new Object[] {id, expectedVersion}, () -> {
      ClientRelease current = get(id);
      if (current.status() != ReleaseStatus.DRAFT && current.status() != ReleaseStatus.SCHEDULED) throw invalidState("Only unexposed drafts or scheduled releases can be archived");
      ClientRelease next = copy(current, ReleaseStatus.ARCHIVED, current.rolloutPercent(), current.scheduledAt(), current.publishedAt(), context.actorId(), expectedVersion);
      if (!releaseRepository.update(next, expectedVersion)) throw rowConflict(); ClientRelease saved = get(id); audit(context, ReleaseOperationAction.ARCHIVE, id, null, current.status(), saved.status()); return saved;
    });
  }
  @Transactional public ClientReleasePolicy upsertPolicy(WriteContext context, ReleasePlatform platform, ReleaseChannel channel, Long expectedVersion, long minimumBuild) {
    return idempotent(context, ReleaseOperationAction.UPSERT_POLICY, new Object[] {platform, channel, expectedVersion, minimumBuild}, () -> {
      requireEnabled(platform); boolean released = releaseRepository.findEligible(platform, channel, TargetArchitecture.UNIVERSAL).stream().anyMatch(item -> item.status() == ReleaseStatus.RELEASED && item.buildNumber() == minimumBuild);
      if (!released) throw invalid("Minimum build must be a released build with a universal artifact"); LocalDateTime now = LocalDateTime.now(Clock.systemUTC()); ClientReleasePolicy current = policyRepository.find(platform, channel).orElse(null); ClientReleasePolicy saved;
      if (current == null) saved = policyRepository.save(new ClientReleasePolicy(null, platform, channel, minimumBuild, 0L, context.actorId(), now, context.actorId(), now));
      else { if (expectedVersion == null || expectedVersion != current.rowVersion() || minimumBuild < current.minimumBuildNumber()) throw rowConflict(); ClientReleasePolicy next = new ClientReleasePolicy(current.id(), platform, channel, minimumBuild, expectedVersion + 1, current.createdBy(), current.createdAt(), context.actorId(), now); if (!policyRepository.update(next, expectedVersion)) throw rowConflict(); saved = policyRepository.find(platform, channel).orElseThrow(); }
      audit(context, ReleaseOperationAction.UPSERT_POLICY, null, saved.id(), null, null); return saved;
    });
  }
  @Transactional public int publishScheduledDue() {
    int count = 0; for (ClientRelease current : releaseRepository.findScheduledDue(LocalDateTime.now(Clock.systemUTC()), properties.getScheduleBatchSize())) { ReleaseStatus status = current.rolloutPercent() == 100 ? ReleaseStatus.RELEASED : ReleaseStatus.ROLLING_OUT; ClientRelease next = copy(current, status, current.rolloutPercent(), current.scheduledAt(), LocalDateTime.now(Clock.systemUTC()), 0L, current.rowVersion()); if (releaseRepository.update(next, current.rowVersion())) { String scheduledOperation = "scheduled:" + current.id() + ":" + current.rowVersion(); WriteContext context = new WriteContext(0L, "scheduler", UUID.nameUUIDFromBytes(scheduledOperation.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), "Scheduled publish"); audit(context, ReleaseOperationAction.SCHEDULED_PUBLISH, current.id(), null, current.status(), status); count++; } } return count;
  }
  private ClientRelease transition(WriteContext context, long id, long version, ReleaseOperationAction action, ReleaseStatus from, ReleaseStatus to) {
    return idempotent(context, action, new Object[] {id, version}, () -> { ClientRelease current = get(id); requireState(current, from); ClientRelease next = copy(current, to, current.rolloutPercent(), current.scheduledAt(), current.publishedAt(), context.actorId(), version); if (!releaseRepository.update(next, version)) throw rowConflict(); ClientRelease saved = get(id); audit(context, action, id, null, current.status(), saved.status()); return saved; });
  }
  private ClientRelease copy(ClientRelease current, ReleaseStatus status, int percent, LocalDateTime scheduledAt, LocalDateTime publishedAt, long actorId, long version) { return new ClientRelease(current.id(), current.platform(), current.channel(), current.version(), current.buildNumber(), status, current.mandatory(), percent, current.rolloutSalt(), scheduledAt, publishedAt, current.releaseNotes(), current.storeUrl(), current.compatibility(), version + 1, current.createdBy(), current.createdAt(), actorId, LocalDateTime.now(Clock.systemUTC()), current.artifacts()); }
  @SuppressWarnings("unchecked") private <T> T idempotent(WriteContext context, ReleaseOperationAction action, Object payload, Supplier<T> operation) {
    String requestDigest = digest(payload); ClientReleaseOperation existing = operationRepository.findByIdempotencyKey(context.idempotencyKey()).orElse(null);
    if (existing != null) { if (existing.action() != action || !existing.requestDigest().equals(requestDigest)) throw conflict("CLIENT_RELEASE_IDEMPOTENCY_REUSED", "Idempotency key is already bound to a different request"); if (existing.releaseId() != null) return (T) get(existing.releaseId()); return (T) policyRepository.findAll().stream().filter(item -> item.id().equals(existing.policyId())).findFirst().orElseThrow(); }
    T saved = operation.get(); Long releaseId = saved instanceof ClientRelease item ? item.id() : null; Long policyId = saved instanceof ClientReleasePolicy item ? item.id() : null; operationRepository.save(new ClientReleaseOperation(null, context.idempotencyKey(), action, requestDigest, releaseId, policyId, context.actorId(), LocalDateTime.now(Clock.systemUTC()))); return saved;
  }
  private void audit(WriteContext context, ReleaseOperationAction action, Long releaseId, Long policyId, ReleaseStatus before, ReleaseStatus after) { auditRepository.append(new ReleaseAuditLog(null, releaseId, policyId, action, before, after, context.requestId(), context.idempotencyKey(), context.reason(), digest(new Object[] {action, releaseId, policyId, before, after}), context.actorId(), LocalDateTime.now(Clock.systemUTC()))); }
  private void validateDraft(ClientRelease value) { if (value.version() == null || !value.version().matches("\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?") || value.buildNumber() == null || value.buildNumber() < 1 || value.releaseNotes() == null || value.releaseNotes().isBlank() || value.compatibility() == null || value.compatibility().protocolVersion() == null || value.compatibility().minimumServerCapabilityVersion() == null || value.artifacts() == null || value.artifacts().isEmpty()) throw invalid("Invalid release draft"); value.artifacts().forEach(artifact -> artifactValidator.validate(value.platform(), value.storeUrl(), artifact)); }
  private void requireEnabled(ReleasePlatform platform) { if (!isEnabled(platform)) throw new ApiException(HttpStatusCodes.BAD_REQUEST, "CLIENT_RELEASE_PLATFORM_NOT_ENABLED", "Platform is reserved until its client is enabled"); }
  private void requireState(ClientRelease release, ReleaseStatus expected) { if (release.status() != expected) throw invalidState("Invalid release state"); }
  private String digest(Object payload) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(payload))); } catch (NoSuchAlgorithmException | JsonProcessingException exception) { throw new IllegalStateException("Cannot create request digest", exception); } }
  private ApiException invalid(String message) { return new ApiException(HttpStatusCodes.BAD_REQUEST, "CLIENT_RELEASE_INVALID_REQUEST", message); }
  private ApiException notFound(String message) { return new ApiException(HttpStatusCodes.NOT_FOUND, "CLIENT_RELEASE_NOT_FOUND", message); }
  private ApiException conflict(String code, String message) { return new ApiException(HttpStatusCodes.CONFLICT, code, message); }
  private ApiException invalidState(String message) { return conflict("CLIENT_RELEASE_INVALID_STATE", message); }
  private ApiException rowConflict() { return conflict("CLIENT_RELEASE_ROW_VERSION_CONFLICT", "Release or policy changed by another request"); }
  public record WriteContext(long actorId, String requestId, String idempotencyKey, String reason) { }
}
