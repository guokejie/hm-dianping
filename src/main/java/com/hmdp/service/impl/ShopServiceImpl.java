package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 添加商铺缓存
     *
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        // Shop shop = queryWithPassThrough(id);
        // 用互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        // 返回
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {
        // 1.从Redis查商铺缓存
        // 这里存的是商铺，它是一个对象
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在（命中真实数据会进来）
        if (StrUtil.isNotBlank(shopJson)) { // 空字符串进不来
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值（走到这里的话，就说明是空字符串）
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.开始实现缓存重建
        // 4.1获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2判断是否获取互斥锁成功
            if (!isLock) {
                // 4.3失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4成功，根据id查询数据库
            shop = getById(id);
            // 5.查询数据库结果为不存在，返回错误
            if (shop == null) {
                // 将空值写入到Redis，过期时间不能设置的太长了
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6.查询数据库结果为存在，写入redis，value是把shop转成json形式，添加过期时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(lockKey);
        }
        // 7.返回
        return shop;
    }


    /**
     * 缓存穿透代码
     *
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        // 1.从Redis查商铺缓存
        // 这里存的是商铺，它是一个对象
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在（命中真实数据会进来）
        if (StrUtil.isNotBlank(shopJson)) { // 空字符串进不来
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值（走到这里的话，就说明是空字符串）
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }
        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);
        // 5.查询数据库结果为不存在，返回错误
        if (shop == null) {
            // 将空值写入到Redis，过期时间不能设置的太长了
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.查询数据库结果为存在，写入redis，value是把shop转成json形式，添加过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7.返回
        return shop;
    }


    /**
     * 尝试加锁
     *
     * @param key 这个锁就是redis中存储的key
     * @return
     */
    private boolean tryLock(String key) {
        // 执行string中的setnx
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        /**
         * 这里为什么这么写呢？
         * 空值处理：在进行自动拆箱时，如果 Boolean 对象为 null，会导致 NullPointerException。因此，在使用自动拆箱时，需要确保对象不是 null。
         * 该函数：
         *  当传入的参数为 null 时，BooleanUtil.isTrue() 通常返回 false。这样可以避免 NullPointerException，并确保代码的健壮性。
         *  如果传入的参数是一个布尔值（boolean 或 Boolean），则该方法会返回该值是否为 true。
         */
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    @Transactional // 如果有抛异常则需要回滚
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
