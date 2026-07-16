package com.xianyusmart.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.context.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * MyBatis Plus配置类
 */
@Configuration
public class MybatisPlusConfig {

    private static final Set<String> TENANT_TABLES = Set.of(
            "xianyu_account", "xianyu_cookie", "xianyu_goods", "xianyu_chat_message",
            "xianyu_goods_config", "xianyu_goods_auto_delivery_config", "xianyu_goods_order",
            "xianyu_goods_auto_reply_record", "xianyu_operation_log", "xianyu_sys_setting",
            "xianyu_kami_config", "xianyu_kami_item", "xianyu_kami_usage_record",
            "xianyu_keyword_reply_rule", "xianyu_keyword_reply_content", "xianyu_goods_sku",
            "xianyu_goods_sku_property", "xianyu_human_intervention_record",
            "merchant_resource", "merchant_task", "merchant_distribution", "merchant_short_link"
    );

    /**
     * 分页插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tenantId = TenantContext.get();
                return new LongValue(tenantId == null ? 0L : tenantId);
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 定时任务没有登录上下文，按账号归属处理全部租户数据。
                return TenantContext.get() == null || !TENANT_TABLES.contains(tableName.toLowerCase());
            }
        }));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
