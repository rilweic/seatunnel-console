package com.dataintegration.console.server.persistence;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.dataintegration.console.common.UuidV7;
import com.zaxxer.hikari.HikariDataSource;

import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

@Configuration(proxyBeanMethods = false)
@MapperScan("com.dataintegration.console.server.persistence.mapper")
public class PersistenceConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /** Primary keys are UUID v7 strings (docs/schema.md §1); numeric ids are never generated. */
    @Bean
    public IdentifierGenerator identifierGenerator() {
        return new IdentifierGenerator() {
            @Override
            public Number nextId(Object entity) {
                throw new UnsupportedOperationException("numeric ids are not used, use IdType.ASSIGN_UUID");
            }

            @Override
            public String nextUUID(Object entity) {
                return UuidV7.next();
            }
        };
    }

    @Bean
    public MetaObjectHandler timestampFiller() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                // Not strictUpdateFill: it skips non-null values, so an entity read from the
                // database would write its old updated_at back.
                setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
            }
        };
    }

    /**
     * Puts every pooled connection in the console's time zone. The console writes local times, but
     * columns MySQL fills itself (DEFAULT / ON UPDATE CURRENT_TIMESTAMP) use the session time zone,
     * which is the server's (often UTC in containers) and would then be hours off.
     */
    @Bean
    public static BeanPostProcessor sessionTimeZone() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource dataSource && dataSource.getConnectionInitSql() == null) {
                    ZoneOffset offset = ZoneId.systemDefault().getRules().getOffset(Instant.now());
                    dataSource.setConnectionInitSql("SET time_zone = '" + (offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId()) + "'");
                }
                return bean;
            }
        };
    }
}
