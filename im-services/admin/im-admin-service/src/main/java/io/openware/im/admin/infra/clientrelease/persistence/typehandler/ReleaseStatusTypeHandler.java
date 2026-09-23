package io.openware.im.admin.infra.clientrelease.persistence.typehandler;
import io.openware.im.admin.domain.clientrelease.model.ReleaseStatus;
public class ReleaseStatusTypeHandler extends DatabaseValueEnumTypeHandler<ReleaseStatus> { protected String databaseValue(ReleaseStatus value) { return value.databaseValue(); } protected ReleaseStatus fromDatabaseValue(String value) { return ReleaseStatus.fromDatabaseValue(value); } }
