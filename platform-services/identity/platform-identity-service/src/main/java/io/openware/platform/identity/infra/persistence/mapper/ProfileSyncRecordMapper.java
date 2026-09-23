package io.openware.platform.identity.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.identity.infra.persistence.po.ProfileSyncRecordPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProfileSyncRecordMapper extends BaseMapper<ProfileSyncRecordPo> {
}
