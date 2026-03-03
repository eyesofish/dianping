package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.尝试从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否存在
        if (StrUtil.isNotBlank(json)) { // 判断字符串既不为null，也不是空字符串(""),且也不是空白字符
            // 3.存在，返回商铺信息
            return JSONUtil.toBean(json, type);

        }
        // 判断是否为空值
        if (json != null) {
            return null;
        }
        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.判断数据库中是否存在
        if (r == null) {
            // 6.不存在，返回错误状态码
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 7.存在，写入redis，返回商铺信息
        this.set(key, r, time, unit);

        return r;

    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.尝试从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否存在
        if (StrUtil.isBlank(json)) { // 判断字符串既不为null，也不是空字符串(""),且也不是空白字符
            // 3.不存在，返回商铺信息
            return null;

        }

        // 4.存在，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R shop = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return shop;
        }
        // 5.2.已过期，尝试异步缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = false;
            try {
                // watch dog 自动续期，防止重建时间超过租期
                locked = lock.tryLock(0, TimeUnit.SECONDS);
                if (!locked) {
                    return;
                }
                R r1 = dbFallback.apply(id);
                this.setWithLogicalExpire(key, r1, time, unit);
            } catch (Exception e) {
                log.error("缓存重建失败", e);
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        });

        // 6.返回过期的商铺信息
        return shop;

    }
}
