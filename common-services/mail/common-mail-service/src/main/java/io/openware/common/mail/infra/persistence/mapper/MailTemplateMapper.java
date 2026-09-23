package io.openware.common.mail.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.common.mail.infra.persistence.po.MailTemplatePo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MailTemplateMapper extends BaseMapper<MailTemplatePo> {
}
