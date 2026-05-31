package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Qualifier("redisTemplate")
    @Autowired
    private RedisTemplate redisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) throws InterruptedException {
//        缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if(shop==null)
            return Result.fail("店铺不存在");
        return Result.ok(shop);
    }

/*    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
//        判断命中的是否是空值
        if (shopJson != null) {
            return null;
        }
//        redis没有，查数据库
        Shop shop = getById(id);
        if (shop == null) {
//            将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            返回错误信息
            return null;
        }
//        存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }*/

    //互斥锁
  /*  public Shop queryWithMutex(Long id) {
        //1.从redis查询商铺缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(shopJson)) {
            //存在，返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断命中是否为空值
        if (shopJson != null) {
            return null;
        }

        //实现缓存重建
        //获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean islock = tryLock(lockKey);

            //失败，则休眠并重试
            if (!islock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //不存在，根据id查询你数据库
            shop = getById(id);

            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                //不存在，返回错误信息
                return null;
            }

            //然后再写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unlock(lockKey);
        }

        //返回
        return shop;
    }*/
    /*private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id) throws InterruptedException {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
//缓存中没有，返回null
            return null;
        }
//        命中，先把shopJson反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
//        判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //        未过期，直接返回
            return shop;
        }
//        过期，缓存重建
//获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);


        if(isLock) {
            //        获取到锁，开启独立线程，缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    savaShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
//                释放锁
                    unlock(lockKey);
                }
            });

        }
        return shop;
        }

//获取锁
    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
       return BooleanUtil.isTrue(flag);
    }
//释放锁
    public void unlock(String key){
       stringRedisTemplate.delete(key);
    }

    public void savaShop2Redis(Long id ,Long expireSeconds) throws InterruptedException {
//        查询数据库
        Shop shop = getById(id);
        Thread.sleep(200);
//        封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }*/
    @Override
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
//        更新数据库，删除缓存
        if (shop==null) {
            return Result.fail("店铺不存在");
        }
        updateById(shop);
        redisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
