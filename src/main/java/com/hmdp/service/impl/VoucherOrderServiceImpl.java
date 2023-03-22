package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.Collections;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author myw
 * @since 2023
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisWorker redisWorker;
    @Resource
    private RedissonClient redissonClient;

    public static final DefaultRedisScript<Long> SEKILL_SCRIPT;
    static {
        SEKILL_SCRIPT=new DefaultRedisScript<>();
        SEKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SEKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SEKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        //2.判断是否有购买资格（为0）
        int r = result.intValue();
        if (r != 0){
            return Result.fail(r == 1 ? "库存不足":"不能重复下单");
        }
        //有购买资格，把下单信息存入阻塞队列
        long orderId = redisWorker.nextId("order");
        //TODO 保存阻塞队列
        //3.返回订单id
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//      if (voucher.getEndTime().isBefore(LocalDateTime.now())){
//          return Result.fail("秒杀已结束");
//      }
//        //判断库存是否充足
//        if (voucher.getStock()<1){
//            return Result.fail("库存不足");
//        }
//        //1.创建锁对象
//        Long userId = UserHolder.getUser().getId();
//        //SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//        //改用Redisson获取锁对象
//        RLock lock = redissonClient.getLock("lock:order" + userId);
//        //2.获取锁
//        boolean isLock = lock.tryLock();
//        if (!isLock){
//            //获取失败
//            return Result.fail("一人限购一单");
//
//        }
//        try {
//            return creatVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//    }
    @Transactional
    public Result creatVoucherOrder(Long voucherId) {
        //优化：一人一单
        //查询订单
        Long id = UserHolder.getUser().getId();
        int count = query().eq("user_id", id).eq("voucher_id", voucherId).count();
        //判断是否存在
        if (count>0){
            return Result.fail("限购一单");
        }
        //扣减库存
        boolean success = seckillVoucherService
                .update().setSql("stock = stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)
                //.eq("stock",voucher.getStock())//乐观锁：cas 通过库存来判断是否被修改过 用于数据修改时
                .update();
        if (!success){
            return Result.fail("库存不足");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisWorker.nextId("order");
        voucherOrder.setVoucherId(voucherId);
        //用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setId(orderId);
        save(voucherOrder);
        //返回订单id
        return Result.ok(orderId);
    }
}
