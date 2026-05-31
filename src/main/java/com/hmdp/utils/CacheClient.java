package com.hmdp.utils;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }
    public void setWithLogicalExpire(String key, Object value, long time, TimeUnit timeUnit) {
//        设计逻辑过期
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        redisData.setData(value);
//        写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
//        从redis查商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            R r = JSONUtil.toBean(json, type);
            return r;
        }
//        判断命中的是否是空值
        if (json != null) {
            return null;
        }
//        redis没有，查数据库
        R r=dbFallback.apply(id);
        if (r == null) {
//            将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", time, timeUnit);
//            返回错误信息
            return null;
        }
//        存在，写入redis
        this.set(key, r, time, timeUnit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,long time, TimeUnit timeUnit) throws InterruptedException {
        String key = keyPrefix + id;
//        从redis查商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
//缓存中没有，返回null
            return null;
        }
//        命中，先把shopJson反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
//        判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //        未过期，直接返回
            return r;
        }
//        过期，缓存重建
//获取互斥锁
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);
        
        if(isLock) {
            //        获取到锁，开启独立线程，缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
//                  查询数据库
                    R r1 = dbFallback.apply(id);
//                    写入redis
                    setWithLogicalExpire(key, r1, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
//                释放锁
                    unlock(lockKey);
                }
            });

        }
        return r;
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
}
