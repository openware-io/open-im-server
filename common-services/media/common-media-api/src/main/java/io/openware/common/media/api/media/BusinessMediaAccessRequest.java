package io.openware.common.media.api.media;

/** Request made by a domain service after it authoritatively verifies a business resource. */
/**
 * A media URL request that has already been authorized by the calling business service.
 *
 * <p>The support service verifies the signed internal caller, while the caller remains the
 * authority for the end user's access to its business resource. A caller-provided user id is
 * deliberately not accepted here because it is not an authorization credential.</p>
 */
public record BusinessMediaAccessRequest(String objectId, String businessType, String businessId) { }
