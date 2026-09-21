package com.gvchat.im.admin.infra.clientrelease.persistence.typehandler;
import com.gvchat.im.admin.domain.clientrelease.model.TargetArchitecture;
public class TargetArchitectureTypeHandler extends DatabaseValueEnumTypeHandler<TargetArchitecture> { protected String databaseValue(TargetArchitecture value) { return value.databaseValue(); } protected TargetArchitecture fromDatabaseValue(String value) { return TargetArchitecture.fromDatabaseValue(value); } }
