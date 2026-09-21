package com.gvchat.im.user.infra.persistence.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.user.infra.persistence.account.po.UserSecurityQuestionPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserSecurityQuestionMapper extends BaseMapper<UserSecurityQuestionPo> {
}
