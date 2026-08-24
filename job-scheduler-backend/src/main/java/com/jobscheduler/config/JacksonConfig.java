package com.jobscheduler.config;


import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Without this, Jackson tries to serialize Hibernate's lazy-loading proxy
 * objects directly (e.g. org.hibernate.proxy.pojo.bytebuddy.ByteBuddyInterceptor)
 * whenever an entity (or something holding a reference to one) ends up on a
 * response path, and throws:
 *   InvalidDefinitionException: Type definition error: [simple type, class
 *   org.hibernate.proxy.pojo.bytebuddy.ByteBuddyInterceptor]
 *
 * Hibernate6Module teaches Jackson to unwrap proxies properly instead of
 * crashing on them. FORCE_LAZY_LOADING=false means uninitialized lazy
 * associations serialize as null instead of triggering an extra DB query.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Hibernate6Module hibernate6Module() {
        Hibernate6Module module = new Hibernate6Module();
        module.configure(Hibernate6Module.Feature.FORCE_LAZY_LOADING, false);
        return module;
    }
}