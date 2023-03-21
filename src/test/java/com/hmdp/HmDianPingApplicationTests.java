package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisWorker redisWorker;

    @Test
    void testSaveShop(){
        shopService.saveShop2Redis(1l,10L);
    }

    @Test
    void testIDWorker(){
        long time = redisWorker.nextId("time");
        System.out.println(time);
    }
}
