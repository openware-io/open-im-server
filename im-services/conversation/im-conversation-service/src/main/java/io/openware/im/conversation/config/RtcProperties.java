package io.openware.im.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.conversation.rtc")
public record RtcProperties(
    boolean enabled,
    String turnUrl,
    String turnUsername,
    String turnPassword) {}
