package com.hmdp.bench;

import com.hmdp.HmDianPingApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.junit.RabbitAvailable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest(
        classes = HmDianPingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("bench")
@Import(RabbitMqBenchIT.BenchRabbitConfig.class)
@RabbitAvailable
class RabbitMqBenchIT {

    static final String EXCHANGE = "bench.exchange";
    static final String QUEUE = "bench.queue";
    static final String ROUTING_KEY = "bench.order";

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private BenchConsumer consumer;

    @AfterEach
    void clearQueue() {
        while (rabbitTemplate.receive(QUEUE) != null) {
            // drain queue
        }
        consumer.reset();
    }

    @Test
    void experiment1_consumerLimit() throws Exception {
        runAndPrint("Experiment1", 16, 20000, 0);
    }

    @Test
    void experiment2_concurrencyScaling() throws Exception {
        runAndPrint("Experiment2", 16, 20000, 0);
    }

    @Test
    void experiment3_prefetchTuning() throws Exception {
        runAndPrint("Experiment3", 16, 20000, 0);
    }

    @Test
    void experiment4_peakShaving() throws Exception {
        runAndPrint("Experiment4", 32, 50000, 0);
    }

    private void runAndPrint(String name, int threads, int totalMessages, int rateLimitMs) throws Exception {
        BenchMetrics m = consumer.reset();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(totalMessages);
        AtomicLong seq = new AtomicLong(1);
        long sendStart = System.currentTimeMillis();

        for (int i = 0; i < totalMessages; i++) {
            pool.submit(() -> {
                try {
                    long id = seq.getAndIncrement();
                    BenchVoucherOrderMessage msg = new BenchVoucherOrderMessage();
                    msg.setOrderId(id);
                    msg.setUserId(id % 10000);
                    msg.setVoucherId(1L);
                    msg.setSendTimestamp(System.currentTimeMillis());
                    rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, msg);
                    m.markProduced(System.currentTimeMillis());
                    if (id % 1000 == 0) {
                        System.out.println("[bench] produced=" + id);
                    }
                    if (rateLimitMs > 0) {
                        Thread.sleep(rateLimitMs);
                    }
                } catch (Exception ignore) {
                    // benchmark run ignores transient producer errors
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long sendEnd = System.currentTimeMillis();
        pool.shutdown();

        Thread.sleep(3000);

        long sendTime = Math.max(1, sendEnd - sendStart);
        long consumeTime = Math.max(1, m.lastConsumeTs() - m.firstConsumeTs());
        double producerQps = totalMessages * 1000.0 / sendTime;
        double consumerQps = m.consumed() == 0 ? 0.0 : m.consumed() * 1000.0 / consumeTime;

        Properties qProps = amqpAdmin.getQueueProperties(QUEUE);
        Object backlog = qProps == null ? -1 : qProps.get("QUEUE_MESSAGE_COUNT");

        System.out.println("==== " + name + " Result ====");
        System.out.println("send_time_ms=" + sendTime);
        System.out.println("producer_qps=" + producerQps);
        System.out.println("consumer_qps=" + consumerQps);
        System.out.println("queue_backlog=" + backlog);
        System.out.println("latency_p50_ms=" + m.percentile(0.50));
        System.out.println("latency_p99_ms=" + m.percentile(0.99));
        System.out.println("produced=" + m.produced() + ", consumed=" + m.consumed());
    }

    @Configuration
    static class BenchRabbitConfig {
        @Bean
        DirectExchange benchExchange() {
            return new DirectExchange(EXCHANGE, true, false);
        }

        @Bean
        Queue benchQueue() {
            return QueueBuilder.durable(QUEUE).build();
        }

        @Bean
        Binding benchBinding(Queue benchQueue, DirectExchange benchExchange) {
            return BindingBuilder.bind(benchQueue).to(benchExchange).with(ROUTING_KEY);
        }

        @Bean
        BenchConsumer benchConsumer() {
            return new BenchConsumer();
        }
    }

    static class BenchConsumer {
        private volatile BenchMetrics metrics = new BenchMetrics();

        BenchMetrics reset() {
            metrics = new BenchMetrics();
            return metrics;
        }

        @RabbitListener(queues = QUEUE)
        public void consume(BenchVoucherOrderMessage msg) {
            long now = System.currentTimeMillis();
            long latency = Math.max(0, now - msg.getSendTimestamp());
            metrics.markConsumed(now, latency);
        }
    }
}
