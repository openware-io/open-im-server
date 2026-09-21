package com.gvchat.im.admin.api.clientrelease;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class ClientReleaseDtos {
  private ClientReleaseDtos() { }
  public record ArtifactRequest(@NotBlank String architecture, @NotBlank String packageType, String downloadUrl, String sha256, @Positive Long sizeBytes, String signingMetadata) { }
  public record CompatibilityRequest(@NotBlank String protocolVersion, @NotNull @Positive Integer minimumServerCapabilityVersion) { }
  public interface ReleaseDraftRequest { String platform(); String channel(); String version(); Long buildNumber(); boolean mandatory(); String releaseNotes(); String storeUrl(); CompatibilityRequest compatibility(); List<ArtifactRequest> artifacts(); }
  public record CreateRequest(@NotBlank String platform, @NotBlank String channel, @NotBlank @Pattern(regexp = "\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?") String version, @NotNull @Positive Long buildNumber, boolean mandatory, @NotBlank String releaseNotes, String storeUrl, @NotNull @Valid CompatibilityRequest compatibility, @NotEmpty @Valid List<ArtifactRequest> artifacts, @NotBlank @Size(max = 512) String reason) implements ReleaseDraftRequest { }
  public record UpdateRequest(@NotNull @PositiveOrZero Long expectedRowVersion, @NotBlank String platform, @NotBlank String channel, @NotBlank @Pattern(regexp = "\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?") String version, @NotNull @Positive Long buildNumber, boolean mandatory, @NotBlank String releaseNotes, String storeUrl, @NotNull @Valid CompatibilityRequest compatibility, @NotEmpty @Valid List<ArtifactRequest> artifacts, @NotBlank @Size(max = 512) String reason) implements ReleaseDraftRequest { }
  public record SubmitRequest(@NotNull @PositiveOrZero Long expectedRowVersion, @NotBlank @Size(max = 512) String reason, LocalDateTime scheduledAt, @NotNull @Min(1) @Max(100) Integer initialRolloutPercent) { }
  public record RolloutRequest(@NotNull @PositiveOrZero Long expectedRowVersion, @NotBlank @Size(max = 512) String reason, @NotNull @Min(1) @Max(100) Integer rolloutPercent) { }
  public record StateRequest(@NotNull @PositiveOrZero Long expectedRowVersion, @NotBlank @Size(max = 512) String reason) { }
  public record PolicyRequest(Long expectedRowVersion, @NotBlank @Size(max = 512) String reason, @NotNull @Positive Long minimumBuildNumber) { }
  public record CheckRequest(@NotBlank String platform, @NotBlank String channel, @NotBlank String version, @NotNull @Positive Long buildNumber, @NotBlank String architecture, @NotBlank String protocolVersion, @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String installationId) { }
  public record ArtifactResponse(String architecture, String packageType, String downloadUrl, String sha256, Long sizeBytes, String signingMetadata) { }
  public record ReleaseResponse(Long id, String platform, String channel, String version, Long buildNumber, String status, boolean mandatory, Integer rolloutPercent, LocalDateTime scheduledAt, LocalDateTime publishedAt, String releaseNotes, String storeUrl, Long rowVersion, CompatibilityRequest compatibility, List<ArtifactResponse> artifacts) { }
  public record PolicyResponse(Long id, String platform, String channel, Long minimumBuildNumber, Long rowVersion, LocalDateTime updatedAt) { }
  public record AuditResponse(Long id, String action, String beforeStatus, String afterStatus, String requestId, String reason, Long createdBy, LocalDateTime createdAt) { }
  public record PlatformResponse(String platform, boolean enabled) { }
  public record CheckResponse(String decision, String blockReason, LocalDateTime serverTime, ReleaseResponse target) { }
  public record DataResponse<T>(T data, String requestId) { }
}
