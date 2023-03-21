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
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author myw
 * @since 2023-3-20
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透解决
        //Shop shop = queryWithPassThrough(id);
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑删除解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
//        Shop shop = cacheClient
//                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop==null){
            return Result.fail("店铺不存在！");
        }
        //返回
        return Result.ok(shop);
    }

    //线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑删除解决缓存击穿
    public Shop queryWithLogicalExpire(Long id){
        String key=CACHE_SHOP_KEY+id;
        //1.从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        //3.不存在，直接返回
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        //4.存在，先把Json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回店铺信息
            return shop;
        }
        //过期，重建缓存
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        Boolean isLock = tryLock(lockKey);
        //判断是否获取锁成功
        if (isLock){
            //成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //缓存重建
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //返回过期商品信息
        return shop;
    }


    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id){
        String key=CACHE_SHOP_KEY+id;
        //1.从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        //3.存在，直接返回
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson!=null){
            //返回错误信息
            return null;
        }
        Shop shop = null;
        String lockKey =LOCK_SHOP_KEY+id;
        try {
            //缓存击穿1：实现缓存重建
            //缓存击穿2：获取互斥锁
            Boolean isLock = tryLock(lockKey);
            //缓存击穿3：判断是否获取成功
            //缓存击穿3：判断是否获取成功-失败：休眠并重试
            if(!isLock){
                Thread.sleep(50);
                queryWithMutex(id);//递归
            }
            //缓存击穿3：判断是否获取成功-成功：写入Redis
            //4.不存在，更具id查询数据库
            shop = getById(id);
            //模拟重建延迟
            Thread.sleep(200);
            //5，不存在，返回错误
            if (shop == null){
                //缓存穿透解决方案：在此处存入空值
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //缓存击穿4：释放互斥锁
            unLock(lockKey);
        }

        //返回
        return shop;
    }


    //缓存穿透解决方法
    public Shop queryWithPassThrough(Long id){
            String key=CACHE_SHOP_KEY+id;
            //1.从redis查询缓存
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            //2.判断是否存在
            //3.存在，直接返回
            if (StrUtil.isNotBlank(shopJson)){
                Shop shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            //判断命中的是否是空值
            if (shopJson!=null){
                //返回错误信息
                return null;
            }
            //4.不存在，更具id查询数据库
            Shop shop = getById(id);
            //5，不存在，返回错误
            if (shop == null){
                //缓存穿透解决方案：在此处存入空值
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
            //返回
            return shop;
    }

    //数据预热：向redis中写入店铺数据并设置逻辑过期时间
    public void saveShop2Redis(Long id,Long expireSeconds){
        //查询店铺数据
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }


    //获取锁
    public Boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    //店铺更新
    //主动更新策略：先更新后删除缓存，并在上面查询时写入redis添加超时时间
    @Override
    public Result update(Shop shop) {
        //1.更新数据库
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete("CACHE_SHOP_KEY+id");
        return Result.ok();
    }
}
