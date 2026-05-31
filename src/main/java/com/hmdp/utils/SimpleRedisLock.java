package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String LOCK_KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString();
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSecond) {
//        获取线程标识
        String threadId = ID_PREFIX+Thread.currentThread().getId();
//        获取锁
        String key = LOCK_KEY_PREFIX + name;
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(threadId), timeoutSecond, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unlock() {
       stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(LOCK_KEY_PREFIX + name), ID_PREFIX+Thread.currentThread().getId());
    }


//    public void unlock() {
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
////        获取锁中标识
//        String lockId = stringRedisTemplate.opsForValue().get(LOCK_KEY_PREFIX + name);
////        毀查是否是当前线程
//        if (threadId.equals(lockId)) {
////            释放锁
//            stringRedisTemplate.delete(LOCK_KEY_PREFIX + name);
//        }
//    }
}
