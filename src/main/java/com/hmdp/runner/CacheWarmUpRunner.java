package com.hmdp.runner;

import com.hmdp.service.CacheWarmUpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CacheWarmUpRunner implements CommandLineRunner {
    @Autowired
    private CacheWarmUpService cacheWarmUpService;

    @Override
    public void run(String... args) throws Exception {
        log.info("开始预热");
        cacheWarmUpService.warmUpShopCache();
        log.info("预热完成");
    }
}