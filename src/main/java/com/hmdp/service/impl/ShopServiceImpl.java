package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private static final String AMAP_GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";

    private final StringRedisTemplate stringRedisTemplate;
    private final CacheClient cacheClient;
    private final String amapWebApiKey;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate, CacheClient cacheClient,
            @Value("${amap.web-api-key:}") String amapWebApiKey) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheClient = cacheClient;
        this.amapWebApiKey = amapWebApiKey;
    }

    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("Shop not found");
        }
        return Result.ok(shop);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result saveShop(Shop shop) {
        if (shop == null) {
            return Result.fail("Shop payload is required");
        }
        if (shop.getTypeId() == null) {
            return Result.fail("Shop type is required");
        }
        // Frontend shop editor currently submits the minimal fields, so fill
        // non-null DB columns that have no default values.
        if (StrUtil.isBlank(shop.getImages())) {
            shop.setImages("");
        }
        if (shop.getSold() == null) {
            shop.setSold(0);
        }
        if (shop.getComments() == null) {
            shop.setComments(0);
        }
        if (shop.getScore() == null) {
            shop.setScore(0);
        }

        fillCoordinateIfNecessary(shop, null);
        if (shop.getX() == null || shop.getY() == null) {
            return Result.fail("Cannot resolve shop coordinates from address");
        }

        boolean saved = save(shop);
        if (!saved || shop.getId() == null) {
            return Result.fail("Create shop failed");
        }
        addOrUpdateShopGeo(shop.getTypeId(), shop.getId(), shop.getX(), shop.getY());
        return Result.ok(shop.getId());
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("Shop id is required");
        }

        Shop oldShop = getById(id);
        if (oldShop == null) {
            return Result.fail("Shop not found");
        }

        if (shop.getTypeId() == null) {
            shop.setTypeId(oldShop.getTypeId());
        }
        fillCoordinateIfNecessary(shop, oldShop);
        if (shop.getX() == null || shop.getY() == null) {
            return Result.fail("Cannot resolve shop coordinates from address");
        }

        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("Update shop failed");
        }

        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        syncShopGeoAfterUpdate(oldShop, shop);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr, result.getDistance());
        });

        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance.getValue());
            }
        }
        return Result.ok(shops);
    }

    private void fillCoordinateIfNecessary(Shop target, Shop existing) {
        if ((target.getX() == null) ^ (target.getY() == null)) {
            target.setX(null);
            target.setY(null);
        }
        if (target.getX() != null && target.getY() != null) {
            return;
        }
        if (existing != null
                && StrUtil.isBlank(target.getAddress())
                && StrUtil.isBlank(target.getArea())
                && existing.getX() != null
                && existing.getY() != null) {
            target.setX(existing.getX());
            target.setY(existing.getY());
            return;
        }

        String address = StrUtil.blankToDefault(target.getAddress(), existing == null ? null : existing.getAddress());
        String city = StrUtil.blankToDefault(target.getArea(), existing == null ? null : existing.getArea());
        Point point = geocodeByAmap(address, city);
        if (point != null) {
            target.setX(point.getX());
            target.setY(point.getY());
            return;
        }

        if (existing != null && existing.getX() != null && existing.getY() != null) {
            target.setX(existing.getX());
            target.setY(existing.getY());
        }
    }

    private Point geocodeByAmap(String address, String city) {
        if (StrUtil.isBlank(address)) {
            return null;
        }
        if (StrUtil.isBlank(amapWebApiKey)) {
            log.warn("amap.web-api-key is empty, skip geocode");
            return null;
        }

        String url = UriComponentsBuilder.fromHttpUrl(AMAP_GEOCODE_URL)
                .queryParam("address", address)
                .queryParam("city", city)
                .queryParam("output", "JSON")
                .queryParam("key", amapWebApiKey)
                .build(true)
                .toUriString();
        try {
            String body = HttpRequest.get(url).timeout(3000).execute().body();
            if (StrUtil.isBlank(body)) {
                return null;
            }

            JSONObject response = JSONUtil.parseObj(body);
            if (!"1".equals(response.getStr("status"))) {
                log.warn("amap geocode failed, status={}, info={}, address={}",
                        response.getStr("status"), response.getStr("info"), address);
                return null;
            }

            JSONArray geocodes = response.getJSONArray("geocodes");
            if (geocodes == null || geocodes.isEmpty()) {
                return null;
            }

            String location = geocodes.getJSONObject(0).getStr("location");
            if (StrUtil.isBlank(location) || !location.contains(",")) {
                return null;
            }
            String[] parts = location.split(",");
            if (parts.length != 2) {
                return null;
            }
            return new Point(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        } catch (Exception e) {
            log.error("call amap geocode error, address={}, city={}", address, city, e);
            return null;
        }
    }

    private void syncShopGeoAfterUpdate(Shop oldShop, Shop newShop) {
        String shopId = String.valueOf(newShop.getId());
        if (oldShop.getTypeId() != null && !oldShop.getTypeId().equals(newShop.getTypeId())) {
            stringRedisTemplate.opsForGeo().remove(SHOP_GEO_KEY + oldShop.getTypeId(), shopId);
        }
        addOrUpdateShopGeo(newShop.getTypeId(), newShop.getId(), newShop.getX(), newShop.getY());
    }

    private void addOrUpdateShopGeo(Long typeId, Long shopId, Double x, Double y) {
        if (typeId == null || shopId == null || x == null || y == null) {
            return;
        }
        stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + typeId, new Point(x, y), String.valueOf(shopId));
    }
}
