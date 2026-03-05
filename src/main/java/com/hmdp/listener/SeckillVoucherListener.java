package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.QueueConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillVoucherListener {

    private final VoucherOrderServiceImpl voucherOrderService;

    @RabbitListener(queues = QueueConfig.QUEUE_A)
    public void receivedA(Message message) throws Exception {
        String msg = new String(message.getBody());
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        log.info("consume QA order: {}", voucherOrder);
        voucherOrderService.handleVoucherOrder(voucherOrder);
    }

    @RabbitListener(queues = QueueConfig.DEAD_LETTER_QUEUE_D)
    public void receivedD(Message message) throws Exception {
        String msg = new String(message.getBody());
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        log.info("consume QD order: {}", voucherOrder);
        voucherOrderService.handleVoucherOrder(voucherOrder);
    }
}
