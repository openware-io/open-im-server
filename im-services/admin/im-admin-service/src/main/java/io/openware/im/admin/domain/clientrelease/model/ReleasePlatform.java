package io.openware.im.admin.domain.clientrelease.model;

import java.util.Arrays;

public enum ReleasePlatform {
  ANDROID("android"), IOS("ios"), WINDOWS("windows"), MACOS("macos"), LINUX("linux");
  private final String databaseValue;
  ReleasePlatform(String databaseValue) { this.databaseValue = databaseValue; }
  public String databaseValue() { return databaseValue; }
  public static ReleasePlatform fromDatabaseValue(String value) { return Arrays.stream(values()).filter(item -> item.databaseValue.equals(value)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown release platform: " + value)); }
}
