package com.gvchat.im.user.infra.persistence.privacysetting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.user.infra.persistence.privacysetting.po.UserPrivacySettingPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserPrivacySettingMapper extends BaseMapper<UserPrivacySettingPo> {
}
