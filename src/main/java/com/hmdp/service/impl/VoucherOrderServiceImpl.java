package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
@Resource
private RedisWorker redisWorker;
@Resource
private StringRedisTemplate stringRedisTemplate;
@Resource
private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

//    线程池
    private ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    //    初始化线程池
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        String queueName="stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    //从消息队列中获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

//                    判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
//                    解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
//                    获取失败，说明没有消息，继续下一次循环
//                    获取成功，说明有消息，创建订单
                    handleVoucherOrder(voucherOrder);
//                    ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //从pendingList中获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1  STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

//                    判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，说明pendingList没有异常消息，结束循环
                        break;
                    }
//                    解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
//                    获取失败，说明没有消息，继续下一次循环
//                    获取成功，说明有消息，创建订单
                    handleVoucherOrder(voucherOrder);
//                    ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", record.getId());

                } catch (Exception e) {

                        log.error("处理订单异常", e);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }

                }
            }
        }
    }

    /* private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true) {
                try {
                    //从队列中获取订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }
*/
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取锁
        RLock lock =redissonClient.getLock("lock:order:" + voucherOrder.getUserId());
        boolean isLock = lock.tryLock();
//        获取失败
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }

        try {

            proxy.createVoucherOrder(voucherOrder);
        }
        finally {
            lock.unlock();
        }

    }
private IVoucherOrderService proxy;
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherID) {
        long orderId = redisWorker.nextId("order");
//       执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherID.toString(), UserHolder.getUser().getId().toString(),String.valueOf(orderId));
//        判断是否为0
        int r = result.intValue();
        if (r!=0) {
//            不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }


//        获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

//        返回订单id
        return Result.ok(orderId);
    }
  /*  @Override
    @Transactional
    public Result seckillVoucher(Long voucherID) {
//       执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherID.toString(), UserHolder.getUser().getId().toString());
//        判断是否为0
        int r = result.intValue();
        if (r!=0) {
//            不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
//        为0，有购买资格，把订单信息保存到阻塞队列
        long orderId = redisWorker.nextId("order");
        //        创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
//        订单id
        voucherOrder.setId(orderId);
//        用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
//        优惠券id
        voucherOrder.setVoucherId(voucherID);
//        放入阻塞队列
        orderTasks.add(voucherOrder);

//        获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

//        返回订单id
return Result.ok(orderId);
    }*/
    /*@Override
    @Transactional
    public Result seckillVoucher(Long id) {
//        判断优惠券是否存在
        SeckillVoucher voucher = seckillVoucherService.getById(id);
        if (voucher==null) {
            return Result.fail("优惠券不存在");
        }
//        判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
//        判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
//        判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
//        创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
//获取锁
        boolean isLock = lock.tryLock();
//        获取失败
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }

        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(id);
        }
        finally {
            lock.unlock();
        }

    }*/
@Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherOrder.getVoucherId());
        //        一人一单
        int count = query().eq("user_id", voucherOrder.getUserId()).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count>0){
           log.error("用户已经购买过一次了");
        }
//        充足，扣库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherOrder.getVoucherId()).eq("stock", voucher.getStock()).update();
        if (!success) {
            log.error("扣库存失败");
             return;
        }
//创建订单
        save(voucherOrder);

    }
}
