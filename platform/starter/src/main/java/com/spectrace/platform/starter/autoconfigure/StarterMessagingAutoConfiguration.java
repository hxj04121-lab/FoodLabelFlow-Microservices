package com.spectrace.platform.starter.autoconfigure;

import com.spectrace.platform.starter.messaging.IdempotentConsumer;
import com.spectrace.platform.starter.messaging.LocalAudit;
import com.spectrace.platform.starter.messaging.MessagingProperties;
import com.spectrace.platform.starter.messaging.Outbox;
import com.spectrace.platform.starter.messaging.OutboxRelay;
import java.time.Clock;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox, local audit, outbox relay and idempotent consumer. Each part activates only when the
 * service has the infrastructure it needs (JDBC for the outbox and audit, JDBC and RabbitMQ for the
 * relay and consumer). The tables come from {@code classpath:db/spectrace-platform}.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"})
@ConditionalOnClass({JdbcTemplate.class, PlatformTransactionManager.class})
@EnableConfigurationProperties(MessagingProperties.class)
public class StarterMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock spectraceClock() {
        return Clock.systemUTC();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class, JsonMapper.class})
    static class Persistence {

        @Bean
        @ConditionalOnMissingBean
        Outbox outbox(JdbcTemplate jdbc, JsonMapper json, MessagingProperties properties, Environment environment,
                      Clock clock) {
            String producer = properties.getProducer() != null ? properties.getProducer()
                    : environment.getRequiredProperty("spring.application.name") + "-service";
            return new Outbox(jdbc, json, producer, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        LocalAudit localAudit(JdbcTemplate jdbc, JsonMapper json, Clock clock) {
            return new LocalAudit(jdbc, json, clock);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RabbitTemplate.class)
    @ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class, JsonMapper.class, ConnectionFactory.class})
    static class Messaging {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "spectrace.messaging.relay", name = "enabled", matchIfMissing = true)
        OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                ConnectionFactory connectionFactory, MessagingProperties properties, Clock clock) {
            return new OutboxRelay(jdbc, new TransactionTemplate(transactionManager), connectionFactory, properties, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        IdempotentConsumer idempotentConsumer(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                              JsonMapper json, Clock clock) {
            return new IdempotentConsumer(jdbc, new TransactionTemplate(transactionManager), json, clock);
        }
    }
}
