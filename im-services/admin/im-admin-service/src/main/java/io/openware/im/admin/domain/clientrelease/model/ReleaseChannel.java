package io.openware.im.admin.domain.clientrelease.model;
import java.util.Arrays;
public enum ReleaseChannel { INTERNAL("internal"), BETA("beta"), STABLE("stable"); private final String databaseValue; ReleaseChannel(String databaseValue) { this.databaseValue = databaseValue; } public String databaseValue() { return databaseValue; } public static ReleaseChannel fromDatabaseValue(String value) { return Arrays.stream(values()).filter(item -> item.databaseValue.equals(value)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown release channel: " + value)); } }
