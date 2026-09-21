package com.gvchat.im.accessws.message;

import java.time.Instant;

public record MessageAcceptance(String msgId, Instant acceptedAt) {
}
