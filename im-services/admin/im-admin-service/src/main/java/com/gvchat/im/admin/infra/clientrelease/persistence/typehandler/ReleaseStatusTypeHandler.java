package com.gvchat.im.admin.infra.clientrelease.persistence.typehandler;
import com.gvchat.im.admin.domain.clientrelease.model.ReleaseStatus;
public class ReleaseStatusTypeHandler extends DatabaseValueEnumTypeHandler<ReleaseStatus> { protected String databaseValue(ReleaseStatus value) { return value.databaseValue(); } protected ReleaseStatus fromDatabaseValue(String value) { return ReleaseStatus.fromDatabaseValue(value); } }
