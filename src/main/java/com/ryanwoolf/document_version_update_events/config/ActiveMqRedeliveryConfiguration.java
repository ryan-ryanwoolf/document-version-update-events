package com.ryanwoolf.document_version_update_events.config;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.RedeliveryPolicy;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.connection.CachingConnectionFactory;

/**
 * ActiveMQ <strong>client</strong> redelivery timing after a transacted session rolls back (failed listener).
 * Without tuning, the broker/client default first delay is ~1s, which can look like an in-process retry.
 * Production analogue: SQS visibility timeout backoff (configure on the queue), not application code.
 */
@Configuration
@Profile("!aws")
@ConditionalOnClass(ActiveMQConnectionFactory.class)
public class ActiveMqRedeliveryConfiguration {

    @Bean
    static BeanPostProcessor activeMqRedeliveryPolicyBeanPostProcessor(
            @Value("${app.jms.redelivery.initial-delay-ms:5000}") long initialDelayMs,
            @Value("${app.jms.redelivery.use-exponential-backoff:true}") boolean useExponentialBackoff,
            @Value("${app.jms.redelivery.backoff-multiplier:2.0}") double backOffMultiplier,
            @Value("${app.jms.redelivery.maximum-redelivery-delay-ms:120000}") long maximumRedeliveryDelayMs) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                ActiveMQConnectionFactory activeMq = resolveActiveMq(bean);
                if (activeMq != null) {
                    RedeliveryPolicy policy = new RedeliveryPolicy();
                    policy.setInitialRedeliveryDelay(initialDelayMs);
                    policy.setUseExponentialBackOff(useExponentialBackoff);
                    policy.setBackOffMultiplier(backOffMultiplier);
                    policy.setMaximumRedeliveryDelay(maximumRedeliveryDelayMs);
                    activeMq.setRedeliveryPolicy(policy);
                }
                return bean;
            }

            private ActiveMQConnectionFactory resolveActiveMq(Object bean) {
                if (bean instanceof ActiveMQConnectionFactory factory) {
                    return factory;
                }
                if (bean instanceof CachingConnectionFactory caching
                        && caching.getTargetConnectionFactory() instanceof ActiveMQConnectionFactory factory) {
                    return factory;
                }
                return null;
            }
        };
    }
}
