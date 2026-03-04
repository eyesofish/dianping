package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import cn.hutool.core.util.StrUtil;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 俞洋
 * @since 2025-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    private final ISeckillVoucherService seckillVoucherService;
    private final StringRedisTemplate stringRedisTemplate;

    public VoucherServiceImpl(ISeckillVoucherService seckillVoucherService, StringRedisTemplate stringRedisTemplate) {
        this.seckillVoucherService = seckillVoucherService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        validateSeckillVoucher(voucher);
        // 保存优惠券
        boolean voucherSaved = save(voucher);
        if (!voucherSaved || voucher.getId() == null) {
            throw new IllegalStateException("保存秒杀券主表失败");
        }
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        boolean seckillSaved = seckillVoucherService.save(seckillVoucher);
        if (!seckillSaved) {
            throw new IllegalStateException("保存秒杀券扩展信息失败");
        }
        // 保存秒杀得库存到redis
        try {
            stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
        } catch (DataAccessException e) {
            throw new IllegalStateException("初始化Redis库存失败，请检查Redis服务", e);
        }

    }

    private void validateSeckillVoucher(Voucher voucher) {
        if (voucher == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (voucher.getShopId() == null) {
            throw new IllegalArgumentException("shopId不能为空");
        }
        if (StrUtil.isBlank(voucher.getTitle())) {
            throw new IllegalArgumentException("title不能为空");
        }
        if (voucher.getPayValue() == null || voucher.getActualValue() == null) {
            throw new IllegalArgumentException("payValue和actualValue不能为空");
        }
        if (voucher.getStock() == null || voucher.getStock() <= 0) {
            throw new IllegalArgumentException("stock必须大于0");
        }
        LocalDateTime begin = voucher.getBeginTime();
        LocalDateTime end = voucher.getEndTime();
        if (begin == null || end == null) {
            throw new IllegalArgumentException("beginTime和endTime不能为空");
        }
        if (!end.isAfter(begin)) {
            throw new IllegalArgumentException("endTime必须晚于beginTime");
        }
    }
}
