package com.hmdp.service;

import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class CacheWarmUpService {

    private final IShopService shopService;

    private final IShopTypeService shopTypeService;

    private final com.hmdp.utils.CacheClient cacheClient; // 复用逻辑过期格式

    public CacheWarmUpService(IShopService shopService, IShopTypeService shopTypeService,
            com.hmdp.utils.CacheClient cacheClient) {
        this.shopService = shopService;
        this.shopTypeService = shopTypeService;
        this.cacheClient = cacheClient;

    }

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
