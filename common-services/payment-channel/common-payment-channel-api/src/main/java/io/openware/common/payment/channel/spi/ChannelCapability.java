package io.openware.common.payment.channel.spi;

/** 渠道能力快照；默认 disabled。 */
public record ChannelCapability(String provider, boolean enabled) {
}
