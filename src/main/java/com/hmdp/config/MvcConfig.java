package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 添加拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // token刷新拦截器，该拦截器拦截所有请求（默认就是这样）
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
        // 登录拦截器，该拦截器拦部分请求
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        // 发送验证码和登录不需要拦截
                        "/user/code",
                        "/user/login",
                        // 不需要拦截博客
                        "/blog/hot",
                        // 放行店铺下的所有
                        "/shop/**",
                        // 店铺类型
                        "/shop-type/**",
                        "/upload/**",
                        "/voucher/**"
                ).order(1);
    }
}
