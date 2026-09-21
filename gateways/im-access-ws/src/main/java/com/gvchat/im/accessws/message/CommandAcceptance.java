package com.gvchat.im.accessws.message;

import java.time.Instant;

public record CommandAcceptance(String commandId, Instant acceptedAt) {
}
