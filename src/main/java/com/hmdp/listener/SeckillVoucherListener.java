package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.rabbitmq.client.Channel;
import com.hmdp.config.QueueConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillVoucherListener {

    private final IVoucherOrderService voucherOrderService;
    private final StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = QueueConfig.QUEUE_A)
    public void receivedA(Message message, Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        consumeAndAck(message, "QA", channel, tag, false);
    }

    @RabbitListener(queues = QueueConfig.DEAD_LETTER_QUEUE_D)
    public void receivedD(Message message, Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        consumeAndAck(message, "QD", channel, tag, true);
    }

    private void consumeAndAck(Message message, String queueName, Channel channel, long tag, boolean deadLetterPreCheck) {
        try {
            String msg = new String(message.getBody(), StandardCharsets.UTF_8);
            VoucherOrder voucherOrder;
            try {
                voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
            } catch (Exception parseException) {
                throw new IllegalArgumentException("invalid voucher order json", parseException);
            }
            if (voucherOrder.getId() == null || voucherOrder.getUserId() == null || voucherOrder.getVoucherId() == null) {
                throw new IllegalArgumentException("invalid voucher order message");
            }

            Long orderId = voucherOrder.getId();
            log.info("consume {} order: {}", queueName, voucherOrder);

            if (deadLetterPreCheck) {
                VoucherOrder existedOrder = voucherOrderService.getById(orderId);
                if (existedOrder != null) {
                    stringRedisTemplate.opsForHash().delete(RedisConstants.SECKILL_PENDING_ORDER_KEY, orderId.toString());
                    log.info("skip duplicated dead-letter order and ack directly, orderId={}", orderId);
                    channel.basicAck(tag, false);
                    return;
                }
            }

            voucherOrderService.handleVoucherOrder(voucherOrder);

            VoucherOrder persistedOrder = voucherOrderService.getById(orderId);
            if (persistedOrder != null) {
                stringRedisTemplate.opsForHash().delete(RedisConstants.SECKILL_PENDING_ORDER_KEY, orderId.toString());
                log.info("clear pending order: {}", orderId);
            } else {
                log.warn("order not persisted yet, keep pending. orderId={}", orderId);
            }

            channel.basicAck(tag, false);
        } catch (IllegalArgumentException e) {
            log.error("reject non-retryable message from {}, tag={}, msg={}", queueName, tag,
                    new String(message.getBody(), StandardCharsets.UTF_8), e);
            reject(channel, tag);
        } catch (Exception e) {
            log.error("nack retryable message from {}, tag={}", queueName, tag, e);
            nack(channel, tag);
        }
    }

    private void nack(Channel channel, long tag) {
        try {
            channel.basicNack(tag, false, true);
        } catch (IOException ioException) {
            log.error("basicNack failed, tag={}", tag, ioException);
        }
    }

    private void reject(Channel channel, long tag) {
        try {
            channel.basicReject(tag, false);
        } catch (IOException ioException) {
            log.error("basicReject failed, tag={}", tag, ioException);
        }
    }
}
