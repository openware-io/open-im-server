package io.openware.im.admin.infra.clientrelease.persistence.typehandler;
import io.openware.im.admin.domain.clientrelease.model.ReleasePlatform;
public class ReleasePlatformTypeHandler extends DatabaseValueEnumTypeHandler<ReleasePlatform> { protected String databaseValue(ReleasePlatform value) { return value.databaseValue(); } protected ReleasePlatform fromDatabaseValue(String value) { return ReleasePlatform.fromDatabaseValue(value); } }
