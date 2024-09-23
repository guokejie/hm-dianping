package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            log.debug("手机格式错误");
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6); // 生成随机的6位验证码
        // 4.保存验证码到redis，并设置期限
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // session.setAttribute("code", code);
        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            log.debug("手机格式错误");
            // 校验失败，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 2.校验验证码->从redis中获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode(); // 前端提交过来的验证码
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 3.不一致，报错
            return Result.fail("验证码错误");
        }
        // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        // query()可以实现单表的增删改查，查一个就用one，多个就用list
        User user = query().eq("phone", phone).one();
        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        // 7.保存用户信息到session->保存到redis中
        // 7.1随机生成一个token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 7.2将user对象转换为Hash对象作为存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())); // 将user转换为map
        // 7.3保存数据到redis中
        // 一个put存一个属性，这样与服务器要做多次交互，putAll一次性交互完
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap); // token-userMap
        // 7.4设置token有效期为30分钟，直接这么写过30分钟就没了，只要用户不断的访问我们就需要不断的更新
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 8.返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户信息
        // 这里使用mp中的方法
        save(user);
        return user;
    }
}
