package com.ryanwoolf.document_version_update_events.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

@Configuration
@Profile("!aws")
public class JmsConfig {

    @Bean
    public MessageConverter jmsMessageConverter(ObjectMapper revisionEventObjectMapper) {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(revisionEventObjectMapper);
        converter.setTargetType(MessageType.TEXT);
        converter.setTypeIdPropertyName("_type");
        return converter;
    }

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jmsMessageConverter,
            @Value("${app.jms.listener.concurrency:3-8}") String listenerConcurrency) {
        DefaultJmsListenerContainerFactory jmsListenerContainerFactory = new DefaultJmsListenerContainerFactory();
        jmsListenerContainerFactory.setConnectionFactory(connectionFactory);
        jmsListenerContainerFactory.setSessionTransacted(true);
        jmsListenerContainerFactory.setConcurrency(listenerConcurrency);
        jmsListenerContainerFactory.setMessageConverter(jmsMessageConverter);
        return jmsListenerContainerFactory;
    }

}
