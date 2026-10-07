package io.devreload.mybatis;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * {@code mybatis-reload.enabled=true}일 때 {@link MapperReloadService}를 등록한다.
 */
@AutoConfiguration(afterName = "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration")
@ConditionalOnClass({ SqlSessionFactory.class, XMLMapperBuilder.class })
@ConditionalOnProperty(prefix = MapperReloadProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MapperReloadProperties.class)
public class MapperReloadAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MapperReloadService mapperReloadService(MapperReloadProperties properties,
            ObjectProvider<SqlSessionFactory> sqlSessionFactories) {
        return new MapperReloadService(properties, sqlSessionFactories);
    }
}
