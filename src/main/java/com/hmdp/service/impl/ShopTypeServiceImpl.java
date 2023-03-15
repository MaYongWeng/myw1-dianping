package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author myw
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private  StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //从redid查询缓存
        String shopType = stringRedisTemplate.opsForValue().get("shop:type");
        //判断是否存在
        //存在返回
        if (StrUtil.isNotBlank(shopType)){
            List<ShopType> typeList = JSONUtil.toList(shopType, ShopType.class);
            System.out.println(typeList);
            return Result.ok(typeList);
        }
        //不存在查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //不存在报错
        if (CollectionUtil.isEmpty(typeList)){
            return Result.fail("列表信息不存在");
        }
        //查询到的数据保存redis
        stringRedisTemplate.opsForValue().set("shop:type",JSONUtil.toJsonStr(typeList));
        //返回

        return Result.ok(typeList);
    }
}
