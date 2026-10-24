package com.dataintegration.console.server.persistence;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.dataintegration.console.common.UuidV7;

import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

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
}
