package com.nomad.common.config;

import io.r2dbc.spi.ConnectionFactory;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
@Profile("reactor")
public class ReactorJooqConfig {

    @Bean
    public DSLContext dslContext(ConnectionFactory connectionFactory) {
        return DSL.using(connectionFactory, SQLDialect.POSTGRES);
    }


    @Bean
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }
}