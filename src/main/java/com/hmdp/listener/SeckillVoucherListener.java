package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.QueueConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillVoucherListener {

    private final IVoucherOrderService voucherOrderService;
    private final StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = QueueConfig.QUEUE_A)
    public void receivedA(Message message) {
        consumeAndClearPending(message, "QA");
    }

    @RabbitListener(queues = QueueConfig.DEAD_LETTER_QUEUE_D)
    public void receivedD(Message message) {
        consumeAndClearPending(message, "QD");
    }

    private void consumeAndClearPending(Message message, String queueName) {
        String msg = new String(message.getBody(), StandardCharsets.UTF_8);
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        Long orderId = voucherOrder.getId();
        log.info("consume {} order: {}", queueName, voucherOrder);
        voucherOrderService.handleVoucherOrder(voucherOrder);
        if (orderId != null) {
            VoucherOrder persistedOrder = voucherOrderService.getById(orderId);
            if (persistedOrder != null) {
                stringRedisTemplate.opsForHash().delete(RedisConstants.SECKILL_PENDING_ORDER_KEY, orderId.toString());
                log.info("clear pending order: {}", orderId);
            } else {
                log.warn("order not persisted yet, keep pending. orderId={}", orderId);
            }
        }
    }
}
