# 本地生活服务平台（hm-dianping）

一个基于 Spring Boot + Redis + Nginx 的本地生活服务点评与秒杀平台，实现了店铺浏览、优惠券秒杀、探店笔记分享、点赞互动等核心功能。前后端分离架构，开箱即用。

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.3.12 | 基础框架 |
| MyBatis-Plus | 3.4.3 | ORM 框架 |
| MySQL | 8.0 | 关系型数据库 |
| Redis | 6.x+ | 缓存、分布式锁、消息队列（Stream） |
| Lettuce | (Spring Boot 管理) | Redis 客户端（连接池） |
| Redisson | 3.13.6 | Redis 分布式锁 |
| Hutool | 5.7.17 | Java 工具库 |
| Lombok | 1.18.30 | 代码简化 |

### 前端

| 技术 | 说明 |
|------|------|
| Nginx 1.18.0 | Web 服务器 + 反向代理 |
| Vue.js + Element UI | 前端框架（已打包为静态资源） |

## 架构概览

```
┌──────────────────────────────────────────────────────────┐
│                   浏览器 (Browser)                        │
│                   http://localhost:8080                   │
└─────────────────────┬────────────────────────────────────┘
                      │
┌─────────────────────▼────────────────────────────────────┐
│               Nginx 1.18.0 (Port 8080)                    │
│  ┌──────────────────┐   ┌───────────────────────────┐    │
│  │  静态资源服务       │   │  反向代理 /api → 后端       │    │
│  │  html/hmdp/       │   │  http://127.0.0.1:8081    │    │
│  └──────────────────┘   └───────────┬───────────────┘    │
└─────────────────────────────────────┼────────────────────┘
                                      │
┌─────────────────────────────────────▼────────────────────┐
│              Spring Boot (Port 8081)                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐    │
│  │ 登录拦截器 │ │ 店铺控制器 │ │ 秒杀控制器 │ │ 笔记控制器 │    │
│  └─────┬─────┘ └─────┬─────┘ └─────┬─────┘ └─────┬─────┘    │
│        └──────────────┴─────────────┴─────────────┘        │
│                          │                                 │
│  ┌───────────────────────▼────────────────────────────┐   │
│  │                    Service 层                        │   │
│  │  CacheClient  │  SeckillVoucher  │  BlogService     │   │
│  └───────────────────────┬────────────────────────────┘   │
└──────────────────────────┼────────────────────────────────┘
                           │
┌──────────────────────────┼────────────────────────────────┐
│                    数据存储层                                │
│  ┌───────────────────────▼────────────────────────────┐   │
│  │                    MySQL 8.0                        │   │
│  │  tb_user │ tb_shop │ tb_voucher │ tb_blog │ ...    │   │
│  └────────────────────────────────────────────────────┘   │
│  ┌────────────────────────────────────────────────────┐   │
│  │                     Redis                           │   │
│  │  缓存  │  分布式锁  │  Stream 消息队列  │  ZSet 点赞  │   │
│  └────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

## 功能模块

### 1. 用户认证

- 基于 **手机号 + 短信验证码** 登录，验证码存储于 Redis 并设置 2 分钟过期
- 登录后生成 UUID Token，用户信息以 Hash 结构存入 Redis（30 分钟有效期）
- 自定义拦截器实现 **Token 自动续期** 与 **ThreadLocal 线程隔离** 的用户上下文

### 2. 店铺管理

- 店铺列表分页查询，支持按类型筛选、按名称关键字搜索
- 构建通用 `CacheClient` 缓存工具，实现三种缓存策略：
  - **空值缓存**：防止缓存穿透（TTL 2 分钟）
  - **SETNX 互斥锁**：防止缓存击穿（锁 TTL 10 秒）
  - **逻辑过期 + 异步重建**：热点数据在过期后返回旧值，后台线程池异步更新缓存
- 更新店铺时删除缓存，遵循 Cache-Aside 模式

### 3. 优惠券秒杀

- 支持普通优惠券和秒杀优惠券的创建与管理
- **下单流程**：
  1. 秒杀券库存预加载至 Redis（`seckill:stock:{id}`）
  2. 前端发起秒杀请求，后端执行 **Lua 脚本** 原子性地完成：库存校验 → 一人一单校验 → 扣减库存 → 记录用户 → 消息入队
  3. Redis **Stream 消费者组** 异步消费订单消息，写入 MySQL
  4. **Redisson 分布式锁** 保证同一用户同一时刻只有一个下单请求在处理
  5. Pending List 兜底机制：处理失败的消息从 PEL 中重试
- 数据库层面使用 **乐观锁**（`stock = stock - 1 WHERE stock = #{curStock}`）防止超卖
- 自定义 **全局唯一 ID 生成器**（雪花算法变体：时间戳 + Redis 自增序列号）

### 4. 探店笔记

- 用户可发布探店笔记，包含标题、正文、图片（最多 9 张）
- 使用 **Redis Sorted Set** 存储点赞用户，score 为点赞时间戳，实现按时间排序的点赞列表
- 展示最新点赞用户头像（Top 5）
- 热门笔记按点赞数降序排列

### 5. 图片上传

- 支持本地文件存储，采用 **Hash 分片目录** 避免单目录文件过多
- 路径格式：`{baseDir}/{hash_d1}/{hash_d2}/{uuid}.{ext}`

## Redis Key 设计

| Key 格式 | 数据类型 | 过期时间 | 说明 |
|----------|---------|---------|------|
| `login:code:{phone}` | String | 2 min | 短信验证码 |
| `login:token:{token}` | Hash | 30 min | 用户会话信息 |
| `cache:shop:{id}` | String | 30 min (逻辑过期) | 店铺缓存 |
| `lock:shop:{id}` | String (SETNX) | 10 sec | 缓存重建互斥锁 |
| `seckill:stock:{voucherId}` | String | — | 秒杀库存 |
| `seckill:order:{voucherId}` | Set | — | 已下单用户集合 |
| `stream.orders` | Stream | — | 秒杀订单消息队列 |
| `blog:liked:{blogId}` | ZSet | — | 笔记点赞用户列表 |
| `feed:{userId}` | — | — | 用户 Feed 流 |
| `sign:{userId}` | — | — | 用户签到位图 |

## 数据库表

| 表名 | 说明 |
|------|------|
| `tb_user` | 用户账号表 |
| `tb_user_info` | 用户扩展信息 |
| `tb_shop` | 店铺信息 |
| `tb_shop_type` | 店铺分类 |
| `tb_voucher` | 优惠券 |
| `tb_seckill_voucher` | 秒杀优惠券（含库存与时间） |
| `tb_voucher_order` | 优惠券订单 |
| `tb_blog` | 探店笔记 |
| `tb_blog_comments` | 笔记评论 |
| `tb_follow` | 用户关注 |
| `tb_sign` | 每日签到 |

## 快速开始

### 环境要求

- JDK 1.8+
- MySQL 8.0+
- Redis 6.x+
- Maven 3.x+（后端）
- Windows 系统（前端 Nginx 为 Windows 版本）

### 1. 克隆项目

```bash
git clone https://github.com/yeqiu6/hmdp-project.git
cd hmdp-project
```

### 2. 初始化数据库

在 MySQL 中创建数据库并导入建表语句与种子数据：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS hmdp DEFAULT CHARSET utf8mb4;"
mysql -u root -p hmdp < src/main/resources/db/hmdp.sql
```

### 3. 修改后端配置

编辑 `src/main/resources/application.yaml`，修改数据库和 Redis 连接信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC
    username: your_username
    password: your_password
  redis:
    host: your_redis_host
    port: 6379
    password: your_redis_password
```

### 4. 启动后端

```bash
mvn spring-boot:run
```

后端服务运行在 `http://localhost:8081`

### 5. 启动前端

进入 `nginx-1.18.0` 目录，**双击 `nginx.exe`** 即可启动前端服务，Nginx 会在后台运行。

前端服务运行在 `http://localhost:8080`，访问该地址即可进入应用。

> **说明**：Nginx 作为 Web 服务器提供前端静态页面，同时通过反向代理将 `/api` 路径的请求转发到后端 Spring Boot 服务（`http://127.0.0.1:8081`），避免了跨域问题。

如需停止 Nginx，在 `nginx-1.18.0` 目录下打开终端执行：

```bash
nginx.exe -s stop
```

### 6. 运行后端测试

```bash
mvn test
```

## 前端页面

| 页面文件 | 功能 |
|---------|------|
| `index.html` | 首页 — 店铺分类与推荐列表 |
| `shop-list.html` | 店铺列表 — 搜索与筛选 |
| `shop-detail.html` | 店铺详情 — 优惠券领取 |
| `login.html` / `login2.html` | 登录页 — 手机号验证码登录 |
| `info.html` | 个人中心 |
| `info-edit.html` | 编辑个人信息 |
| `other-info.html` | 他人主页 |
| `blog-detail.html` | 笔记详情 |
| `blog-edit.html` | 发布/编辑笔记 |

## 项目结构

```
hmdp/
├── nginx-1.18.0/                     # 前端 — Nginx + 静态页面
│   ├── nginx.exe                     #   Nginx 启动程序（双击运行）
│   ├── conf/nginx.conf               #   Nginx 配置（端口 8080，反向代理 /api → 8081）
│   ├── html/hmdp/                    #   前端静态资源
│   │   ├── index.html                #     首页
│   │   ├── login.html                #     登录页
│   │   ├── shop-list.html            #     店铺列表
│   │   ├── shop-detail.html          #     店铺详情
│   │   ├── blog-detail.html          #     笔记详情
│   │   ├── blog-edit.html            #     发布/编辑笔记
│   │   ├── info.html                 #     个人中心
│   │   ├── info-edit.html            #     编辑资料
│   │   ├── other-info.html           #     他人主页
│   │   ├── css/                      #     样式文件
│   │   ├── js/                       #     JavaScript
│   │   └── imgs/                     #     图片资源
│   └── logs/                         #   Nginx 日志
├── src/
│   ├── main/java/com/hmdp/
│   │   ├── HmDianPingApplication.java      # 启动类
│   │   ├── config/                          # 配置类
│   │   │   ├── MvcConfig.java               #   MVC 拦截器注册
│   │   │   ├── MybatisConfig.java           #   MyBatis-Plus 分页插件
│   │   │   ├── RedissonConfig.java          #   Redisson 客户端
│   │   │   └── WebExceptionAdvice.java      #   全局异常处理
│   │   ├── controller/                      # 控制器层
│   │   │   ├── UserController.java          #   用户接口
│   │   │   ├── ShopController.java          #   店铺接口
│   │   │   ├── ShopTypeController.java      #   店铺分类接口
│   │   │   ├── VoucherController.java       #   优惠券接口
│   │   │   ├── VoucherOrderController.java  #   秒杀下单接口
│   │   │   ├── BlogController.java          #   笔记接口
│   │   │   └── UploadController.java        #   图片上传接口
│   │   ├── dto/                             # 数据传输对象
│   │   │   ├── Result.java                  #   统一响应体
│   │   │   ├── LoginFormDTO.java            #   登录表单
│   │   │   ├── UserDTO.java                 #   用户视图
│   │   │   └── ScrollResult.java            #   滚动分页结果
│   │   ├── entity/                          # 实体类
│   │   ├── mapper/                          # MyBatis Mapper
│   │   ├── service/                         # 服务层接口与实现
│   │   │   └── impl/
│   │   │       ├── UserServiceImpl.java     #   短信登录逻辑
│   │   │       ├── ShopServiceImpl.java     #   多级缓存逻辑
│   │   │       ├── VoucherOrderServiceImpl.java  # 秒杀下单 + Stream 消费
│   │   │       └── BlogServiceImpl.java     #   笔记与点赞逻辑
│   │   └── utils/                           # 工具类
│   │       ├── CacheClient.java             #   通用缓存工具（穿透/击穿/雪崩）
│   │       ├── RedisWorker.java             #   全局唯一 ID 生成器
│   │       ├── SimpleRedisLock.java         #   Redis SETNX 分布式锁
│   │       ├── LoginInterceptor.java        #   登录校验拦截器
│   │       ├── RefreshTokenInterceptor.java #   Token 刷新拦截器
│   │       └── UserHolder.java              #   ThreadLocal 用户上下文
│   └── main/resources/
│       ├── application.yaml                 #   应用配置
│       ├── db/hmdp.sql                      #   数据库初始化脚本
│       ├── seckill.lua                      #   秒杀 Lua 脚本
│       ├── unlock.lua                       #   分布式锁释放脚本
│       └── mapper/                          #   MyBatis XML 映射文件
├── pom.xml                                  # Maven 构建配置
└── README.md
```

## API 接口概览

### 用户

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/user/code` | 发送短信验证码 |
| POST | `/api/user/login` | 登录/注册 |
| GET | `/api/user/me` | 获取当前用户信息 |
| GET | `/api/user/info/{id}` | 获取用户详情 |

### 店铺

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/shop/{id}` | 查询店铺详情 |
| POST | `/api/shop` | 新增店铺 |
| PUT | `/api/shop` | 更新店铺 |
| GET | `/api/shop/of/type` | 按分类查询店铺 |
| GET | `/api/shop/of/name` | 按名称搜索店铺 |

### 优惠券

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/voucher` | 新增普通优惠券 |
| POST | `/api/voucher/seckill` | 新增秒杀优惠券 |
| GET | `/api/voucher/list/{shopId}` | 查询店铺优惠券 |

### 秒杀下单

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/voucher-order/seckill/{id}` | 秒杀下单 |

### 探店笔记

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/blog` | 发布笔记 |
| GET | `/api/blog/hot` | 热门笔记 |
| GET | `/api/blog/{id}` | 笔记详情 |
| PUT | `/api/blog/like/{id}` | 点赞/取消点赞 |
| GET | `/api/blog/likes/{id}` | 查询点赞用户 |

### 图片上传

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/upload/blog` | 上传图片 |
| GET | `/api/upload/blog/delete` | 删除图片 |

> **注意**：API 路径前缀 `/api` 由 Nginx 反向代理时自动去除，实际转发到后端的路径不含 `/api` 前缀。

## 待开发功能

- [ ] 用户关注系统（数据表已建，业务逻辑待实现）
- [ ] 笔记评论系统（数据表已建，业务逻辑待实现）
- [ ] 每日签到功能（数据表已建，业务逻辑待实现）
- [ ] 基于地理位置（GEO）的附近店铺推荐
