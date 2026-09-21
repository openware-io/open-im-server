package com.gvchat.common.media.api.media;

import java.time.Instant;

public record MediaAccessUrlSnapshot(String objectId, String url, String contentType, long size, Instant expiresAt) { }
