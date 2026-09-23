package io.openware.im.message.application.query;

public record MessageSyncQuery(long userId, long afterSyncSeq, Integer limit) {
}
