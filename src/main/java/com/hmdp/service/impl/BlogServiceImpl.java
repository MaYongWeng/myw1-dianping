package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private IBlogService blogService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if (blog==null){
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    //查询blog相关用户是否点过赞
    private void isBlogLiked(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        //判断当前登录用户是否点赞
        String key = "blog:liked"+blog.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }
    //查询blog相关用户
    private void queryBlogUser(Blog blog) {
        //查询blog相关的用户
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        String nickName = user.getNickName();
        String icon = user.getIcon();
        blog.setIcon(icon).setName(nickName);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //判断当前登录用户是否点赞
        String key = "blog:liked"+id;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        //未点赞，数据库点赞数+1
        //保存用户到redis的set集合
        if (BooleanUtil.isFalse(isMember)){
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForSet().add(key,userId.toString());
            }
        }else {
            //已点赞，数据库-1
            //把用户从redis的set集合移除
            update().setSql("liked = liked - 1").eq("id",id).update();
            stringRedisTemplate.opsForSet().remove(key,userId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }
}
