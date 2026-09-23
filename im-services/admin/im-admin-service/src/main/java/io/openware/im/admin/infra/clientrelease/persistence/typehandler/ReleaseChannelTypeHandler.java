package io.openware.im.admin.infra.clientrelease.persistence.typehandler;
import io.openware.im.admin.domain.clientrelease.model.ReleaseChannel;
public class ReleaseChannelTypeHandler extends DatabaseValueEnumTypeHandler<ReleaseChannel> { protected String databaseValue(ReleaseChannel value) { return value.databaseValue(); } protected ReleaseChannel fromDatabaseValue(String value) { return ReleaseChannel.fromDatabaseValue(value); } }
