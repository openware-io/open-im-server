package io.openware.im.admin.infra.clientrelease.persistence.typehandler;
import io.openware.im.admin.domain.clientrelease.model.PackageType;
public class PackageTypeTypeHandler extends DatabaseValueEnumTypeHandler<PackageType> { protected String databaseValue(PackageType value) { return value.databaseValue(); } protected PackageType fromDatabaseValue(String value) { return PackageType.fromDatabaseValue(value); } }
