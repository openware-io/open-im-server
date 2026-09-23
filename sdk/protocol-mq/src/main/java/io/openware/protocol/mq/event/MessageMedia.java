package io.openware.protocol.mq.event;

/** A managed media object and the short-lived URL issued for an authorized message recipient. */
public record MessageMedia(String objectId, String url) { }
