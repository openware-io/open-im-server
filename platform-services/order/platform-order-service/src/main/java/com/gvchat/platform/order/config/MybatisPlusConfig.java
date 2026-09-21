package com.gvchat.platform.order.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {
    /**
     * 租户行级过滤 + 物理分页。
     *
     * <p>分页插件必须显式注册：没有 {@link PaginationInnerInterceptor} 时 {@code selectPage} 不会拼
     * {@code LIMIT}、{@code total} 也恒为 0（表现为「分页静默失效，一次返回全表」）。
     * 顺序与 customer/marketing 等模块一致——先租户过滤，再分页（count 语句也要带上租户条件）。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new PlatformTenantLineHandler()));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
