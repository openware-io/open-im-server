package com.gvchat.im.admin.infra.clientrelease.persistence.typehandler;
import com.gvchat.im.admin.domain.clientrelease.model.ReleaseChannel;
public class ReleaseChannelTypeHandler extends DatabaseValueEnumTypeHandler<ReleaseChannel> { protected String databaseValue(ReleaseChannel value) { return value.databaseValue(); } protected ReleaseChannel fromDatabaseValue(String value) { return ReleaseChannel.fromDatabaseValue(value); } }
