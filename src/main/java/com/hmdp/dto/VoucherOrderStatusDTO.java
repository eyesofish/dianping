package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VoucherOrderStatusDTO {
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private Integer status;
    private String statusDesc;
    private Boolean dbExists;
    private Boolean processing;
    private String message;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
