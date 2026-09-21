package com.gvchat.common.media.api.media;

public record MediaObjectAuthorizationRequest(long ownerId, String objectId, String mediaKind) { }
