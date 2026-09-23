package io.openware.common.media.api.media;

public record MediaObjectAuthorizationSnapshot(boolean authorized, String objectId, String contentType, long size) { }
