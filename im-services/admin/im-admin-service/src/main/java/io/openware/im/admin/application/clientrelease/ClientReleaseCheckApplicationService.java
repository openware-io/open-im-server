package io.openware.im.admin.application.clientrelease;

import io.openware.im.admin.domain.clientrelease.model.*;
import io.openware.im.admin.domain.clientrelease.repository.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Stateless public release decision service. It intentionally performs no audit write. */
@Service
@RequiredArgsConstructor
public class ClientReleaseCheckApplicationService {
  private final ClientReleaseRepository releaseRepository;
  private final ClientReleasePolicyRepository policyRepository;
  private final ClientReleaseProperties properties;
  public CheckResult check(ReleasePlatform platform, ReleaseChannel channel, long buildNumber, TargetArchitecture architecture, String protocolVersion, String installationId) {
    if (!properties.getEnabledPlatforms().contains(platform)) return result("unsupported_client", "platform_not_enabled", null);
    List<ClientRelease> candidates = releaseRepository.findEligible(platform, channel, architecture);
    ClientRelease baseline = candidates.stream().filter(item -> item.status() == ReleaseStatus.RELEASED).findFirst().orElse(null);
    if (baseline == null) return result("no_release", null, null);
    ClientReleasePolicy policy = policyRepository.find(platform, channel).orElse(null);
    if (policy != null && buildNumber < policy.minimumBuildNumber()) return result("unsupported_client", "minimum_build_not_supported", baseline);
    if (!properties.getSupportedProtocolVersions().contains(protocolVersion) || baseline.compatibility().minimumServerCapabilityVersion() > properties.getMinimumServerCapabilityVersion()) return result("unsupported_client", "protocol_not_supported", baseline);
    ClientRelease target = candidates.stream().filter(item -> item.status() == ReleaseStatus.ROLLING_OUT).filter(item -> assigned(item.rolloutSalt(), installationId) < item.rolloutPercent()).findFirst().orElse(baseline);
    if (target.compatibility().minimumServerCapabilityVersion() > properties.getMinimumServerCapabilityVersion()) return result("unsupported_client", "protocol_not_supported", target);
    if (target.buildNumber() <= buildNumber) return result("up_to_date", null, target);
    return result(target.mandatory() ? "mandatory_update" : "optional_update", null, target);
  }
  private CheckResult result(String decision, String blockReason, ClientRelease target) { return new CheckResult(decision, blockReason, target, LocalDateTime.now()); }
  private int assigned(String salt, String installationId) { try { byte[] hash = MessageDigest.getInstance("SHA-256").digest((salt + ":" + installationId).getBytes(StandardCharsets.UTF_8)); return (int) (Integer.toUnsignedLong(ByteBuffer.wrap(hash, 0, Integer.BYTES).getInt()) % 100); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); } }
  public record CheckResult(String decision, String blockReason, ClientRelease target, LocalDateTime serverTime) { }
}
