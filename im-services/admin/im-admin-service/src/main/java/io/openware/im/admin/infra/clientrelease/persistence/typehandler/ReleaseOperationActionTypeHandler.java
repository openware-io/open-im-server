package io.openware.im.admin.infra.clientrelease.persistence.typehandler;
import io.openware.im.admin.domain.clientrelease.model.ReleaseOperationAction;
public class ReleaseOperationActionTypeHandler extends DatabaseValueEnumTypeHandler<ReleaseOperationAction> { protected String databaseValue(ReleaseOperationAction value) { return value.databaseValue(); } protected ReleaseOperationAction fromDatabaseValue(String value) { return ReleaseOperationAction.fromDatabaseValue(value); } }
