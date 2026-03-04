package com.hmdp.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/dev")
public class DevAuthController {

    private static final Long DEFAULT_DEV_USER_ID = 1L;
    private static final String DEFAULT_DEV_PHONE = "13800000000";

    private final IUserService userService;
    private final StringRedisTemplate stringRedisTemplate;

    public DevAuthController(IUserService userService, StringRedisTemplate stringRedisTemplate) {
        this.userService = userService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @PostMapping("/login")
    public Result devLogin() {
        User user = resolveDevUser();
        String token = buildLoginToken(user);
        Map<String, String> data = new HashMap<>(1);
        data.put("token", token);
        return Result.ok(data);
    }

    private User resolveDevUser() {
        User user = userService.getById(DEFAULT_DEV_USER_ID);
        if (user != null) {
            return user;
        }
        user = userService.query().eq("phone", DEFAULT_DEV_PHONE).one();
        if (user != null) {
            return user;
        }
        User newUser = new User();
        newUser.setPhone(DEFAULT_DEV_PHONE);
        newUser.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + "dev_" + RandomUtil.randomString(6));
        userService.save(newUser);
        return newUser;
    }

    private String buildLoginToken(User user) {
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString()));
        String userKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(userKey, userMap);
        stringRedisTemplate.expire(userKey, 30, TimeUnit.MINUTES);
        return token;
    }
}
