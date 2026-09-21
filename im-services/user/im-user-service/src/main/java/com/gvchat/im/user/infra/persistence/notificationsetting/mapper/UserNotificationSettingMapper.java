package com.gvchat.im.user.infra.persistence.notificationsetting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.user.infra.persistence.notificationsetting.po.UserNotificationSettingPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserNotificationSettingMapper extends BaseMapper<UserNotificationSettingPo> {
}
