package io.openware.im.user.infra.persistence.privacysetting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.user.infra.persistence.privacysetting.po.UserPrivacySettingPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserPrivacySettingMapper extends BaseMapper<UserPrivacySettingPo> {
}
