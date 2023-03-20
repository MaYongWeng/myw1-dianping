package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

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

    @Override
    public Result queryById(Long id) {
        //缓存穿透解决
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);

        if (shop==null){
            return Result.fail("店铺不存在！");
        }
        //返回
        return Result.ok(shop);
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
            //失败：休眠并重试
            if(!isLock){
                Thread.sleep(50);
                queryWithMutex(id);//递归
            }
            //成功：写入Redis
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


    //获取锁
    public Boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }

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
