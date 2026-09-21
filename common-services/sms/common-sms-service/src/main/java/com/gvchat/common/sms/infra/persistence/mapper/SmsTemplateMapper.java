package com.gvchat.common.sms.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.common.sms.infra.persistence.po.SmsTemplatePo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SmsTemplateMapper extends BaseMapper<SmsTemplatePo> {
}
