package com.hmdp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitProducerConfig {

    private final RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void initRabbitCallbacks() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String orderId = correlationData != null ? correlationData.getId() : "unknown";
            if (ack) {
                log.info("publisher confirm success, orderId={}, ack={}, cause={}", orderId, true, cause);
            } else {
                log.error("publisher confirm failed, orderId={}, ack={}, cause={}", orderId, false, cause);
            }
        });

        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            log.error("publisher return triggered, exchange={}, routingKey={}, replyCode={}, replyText={}, message={}",
                    exchange, routingKey, replyCode, replyText, body);
        });
    }
}
