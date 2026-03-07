package com.hmdp.task;

import com.hmdp.config.QueueConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOrderCompensateTask {

    private static final int BATCH_SIZE = 20;
    private static final String ROUTING_KEY_A = "XA";

    private final StringRedisTemplate stringRedisTemplate;
    private final IVoucherOrderService voucherOrderService;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelay = 5000)
    public void retryPendingOrders() {
        int scanned = 0;
        int cleaned = 0;
        int resent = 0;
        Cursor<Map.Entry<Object, Object>> cursor = null;
        try {
            cursor = stringRedisTemplate.opsForHash().scan(
                    RedisConstants.SECKILL_PENDING_ORDER_KEY,
                    ScanOptions.scanOptions().count(BATCH_SIZE).build());

            while (cursor.hasNext() && scanned < BATCH_SIZE) {
                Map.Entry<Object, Object> entry = cursor.next();
                scanned++;
                String orderIdStr = String.valueOf(entry.getKey());
                String orderJson = String.valueOf(entry.getValue());

                Long orderId = parseOrderId(orderIdStr);
                if (orderId == null) {
                    deletePending(orderIdStr);
                    cleaned++;
                    continue;
                }

                VoucherOrder dbOrder = voucherOrderService.getById(orderId);
                if (dbOrder != null) {
                    deletePending(orderIdStr);
                    cleaned++;
                    continue;
                }

                try {
                    rabbitTemplate.convertAndSend(QueueConfig.X_EXCHANGE, ROUTING_KEY_A, orderJson,
                            new CorrelationData(orderIdStr));
                    resent++;
                } catch (Exception e) {
                    log.error("pending order resend failed, orderId={}", orderId, e);
                }
            }
        } catch (Exception e) {
            log.error("scan pending orders failed", e);
        } finally {
            closeCursor(cursor);
        }

        if (scanned > 0) {
            log.info("pending compensate finished, scanned={}, cleaned={}, resent={}", scanned, cleaned, resent);
        }
    }

    private Long parseOrderId(String orderIdStr) {
        try {
            return Long.valueOf(orderIdStr);
        } catch (Exception e) {
            log.warn("invalid pending order id, remove it. orderId={}", orderIdStr);
            return null;
        }
    }

    private void deletePending(String orderIdStr) {
        stringRedisTemplate.opsForHash().delete(RedisConstants.SECKILL_PENDING_ORDER_KEY, orderIdStr);
    }

    private void closeCursor(Cursor<?> cursor) {
        if (cursor == null) {
            return;
        }
        try {
            cursor.close();
        } catch (Exception e) {
            log.warn("close redis scan cursor failed", e);
        }
    }
}
