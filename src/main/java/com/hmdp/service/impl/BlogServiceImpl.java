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
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
//查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
            queryBlogUser(blog);
//        查询blog是否被点赞
        isBlogLiked(blog);
            return Result.ok(blog);

    }

    private void isBlogLiked(Blog blog) {
        //        1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
//        2.判断当前用户是否已经点赞
        String key="blog:liked:"+blog.getId();
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isLiked));
    }

    @Override
        public Result queryHotBlog (Integer current){
            // 根据用户查询
            Page<Blog> page = blogService.query()
                    .orderByDesc("liked")
                    .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
            // 获取当前页数据
            List<Blog> records = page.getRecords();
            // 查询用户
            records.forEach(blog -> {
                this.queryBlogUser(blog);
                // 查询blog是否被点赞
                this.isBlogLiked(blog);
            });
            return Result.ok(records);
        }

    @Override
    public Result likeBlog(Long id) {
//        1.获取登录用户
            UserDTO user = UserHolder.getUser();
            Long userId = user.getId();
//        2.判断当前用户是否已经点赞
        String key="blog:liked:"+id;
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(isLiked)) {
            // 3..如果没有点赞，可以点赞
//        3.1数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
//        3.2保存点赞用户到redis中
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        }else {
//        4.如果已经点赞，取消点赞
//        4.1数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
//        4.2把点赞用户从redis中移除
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    private void queryBlogUser (Blog blog){
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }
