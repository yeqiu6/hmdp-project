package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Qualifier("redisTemplate")
    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public Result queryShopType() {
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
//        从redis查商铺类型缓存
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(key,0, -1);
        if(CollectionUtil.isNotEmpty(shopTypeJson)){
//            json字符串转对象，然后返回
            List<ShopType> shopTypeList = shopTypeJson.stream().map(jsonStr -> JSONUtil.toBean(jsonStr, ShopType.class)).collect(Collectors.toList());
            return Result.ok(shopTypeList);

        }
        //            redis没有，查数据库
        List<ShopType> shopList = query().orderByAsc("sort").list();
        if (CollectionUtil.isEmpty(shopList)) {
            return Result.fail("店铺类型不存在");
        }
//        存在，写入redis
//把shopList里的每个元素转成json字符串
        List<String> shopTypesJson = shopList.stream().map(shopType -> JSONUtil.toJsonStr(shopType)).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key,shopTypesJson);


        return Result.ok(shopList);
    }
}
