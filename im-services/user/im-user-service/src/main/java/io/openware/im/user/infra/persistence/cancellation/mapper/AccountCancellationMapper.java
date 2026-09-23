package io.openware.im.user.infra.persistence.cancellation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.user.infra.persistence.cancellation.po.AccountCancellationPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccountCancellationMapper extends BaseMapper<AccountCancellationPo> {
}
