package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 俞洋
 * @since 2025-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    Result queryOrderById(Long orderId);

    Result queryOrderOfMe(Integer current, Integer pageSize);

    void createVoucherOrder(VoucherOrder voucherOrder);

    void handleVoucherOrder(VoucherOrder voucherOrder);
}
