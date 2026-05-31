package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
 class HmDianPingApplicationTests {
    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisWorker redisWorker;

    /*@Test
     void testSaveShop() throws InterruptedException {
        shopService.savaShop2Redis(1L,10L);
    }*/
    @Test
    void testNextId() throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisWorker.nextId("order");
                System.out.println("id="+id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.execute(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时："+(end-begin));
    }
}
