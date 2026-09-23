package io.openware.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.order.infra.persistence.po.KtvServerSessionPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KtvServerSessionMapper extends BaseMapper<KtvServerSessionPo> {

    /** 乐观锁更新：仅当 version 匹配才生效，返回受影响行数（0 = 并发冲突）。 */
    @Update("UPDATE ord_ktv_server_session SET status = #{status}, started_at = #{startedAt}, ended_at = #{endedAt}, " +
            "duration_seconds = #{durationSeconds}, duration_minutes = #{durationMinutes}, total_amount = #{totalAmount}, " +
            "updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE id = #{id} AND version = #{version}")
    int updateWithVersion(KtvServerSessionPo po);

    /** 跨租户鉴别：忽略租户拦截，仅按主键读取 tenant_id（区分「不存在」vs「跨租户」）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT tenant_id FROM ord_ktv_server_session WHERE id = #{id}")
    Long selectTenantIdById(@Param("id") Long id);

    /** 同一包厢会话下的服务人员点单（免费名额按创建顺序判定，需要一次取全再排序）。 */
    @Select("SELECT * FROM ord_ktv_server_session WHERE ktv_session_id = #{ktvSessionId} ORDER BY id ASC")
    List<KtvServerSessionPo> selectByKtvSessionId(@Param("ktvSessionId") Long ktvSessionId);

    /** 同一订单下的服务人员点单（老数据可能没有 ktv_session_id，退化为按订单判定免费名额）。 */
    @Select("SELECT * FROM ord_ktv_server_session WHERE order_id = #{orderId} ORDER BY id ASC")
    List<KtvServerSessionPo> selectByOrderId(@Param("orderId") Long orderId);
}
