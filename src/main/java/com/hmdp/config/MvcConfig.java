package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

        private final StringRedisTemplate stringRedisTemplate;

        public MvcConfig(StringRedisTemplate stringRedisTemplate) {
                this.stringRedisTemplate = stringRedisTemplate;
        }

        @Override
        public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                                .allowedOrigins("http://localhost:5173", "http://localhost:5174")
                                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                                .allowCredentials(true)
                                .maxAge(3600);
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
                // 登录拦截器
                registry.addInterceptor(new LoginInterceptor())
                                .excludePathPatterns(
                                                "/user/login",
                                                "/upload/**",
                                                "/voucher/**",
                                                "/user/code",
                                                "/shop/**",
                                                "/shop-type/**",
                                                "/blog/hot")
                                .order(1);
                // token刷新的拦截器
                registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                                .addPathPatterns("/**").order(0);
        }
}
