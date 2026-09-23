package io.openware.im.admin.domain.clientrelease.model;
import java.util.Arrays;
public enum TargetArchitecture { UNIVERSAL("universal"), X64("x64"), ARM64("arm64"); private final String databaseValue; TargetArchitecture(String databaseValue) { this.databaseValue = databaseValue; } public String databaseValue() { return databaseValue; } public static TargetArchitecture fromDatabaseValue(String value) { return Arrays.stream(values()).filter(item -> item.databaseValue.equals(value)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown architecture: " + value)); } }
