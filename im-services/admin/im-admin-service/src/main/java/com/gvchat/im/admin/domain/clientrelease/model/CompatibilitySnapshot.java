package com.gvchat.im.admin.domain.clientrelease.model;

public record CompatibilitySnapshot(String protocolVersion, Integer minimumServerCapabilityVersion) { }
