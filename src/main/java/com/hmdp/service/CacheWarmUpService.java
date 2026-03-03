package com.hmdp.service;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

@Service
@Slf4j
public class CacheWarmUpService {
    @Resource
    private IShopService shopService;
    @Resource
    private IShopTypeService shopTypeService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private com.hmdp.utils.CacheClient cacheClient; // 复用逻辑过期格式

    public void warmUpShopCache() {
        log.info("开始执行商铺缓存预热");
        List<ShopType> types = shopTypeService.query().orderByAsc("sort").list();
        for (ShopType type : types) {
            List<Shop> shops = shopService.query()
                    .eq("type_id", type.getId())
                    .orderByDesc("score")
                    .last("LIMIT 20")
                    .list();
            for (Shop shop : shops) {
                cacheClient.setWithLogicalExpire(
                        RedisConstants.CACHE_SHOP_KEY + shop.getId(),
                        shop,
                        RedisConstants.CACHE_SHOP_TTL,
                        TimeUnit.MINUTES);
            }
            log.debug("类型 {} 预热 {} 条", type.getName(), shops.size());
        }
        log.info("商铺缓存预热完成，类型数 {}", types.size());
    }
}