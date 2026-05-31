--1.参数列表
--优惠券id
local voucherId=ARGV[1]
--用户id
local userId=ARGV[2]
local orderId=ARGV[3]
--2.数据key
local stockKey='seckill:stock:'..voucherId
local orderKey='seckill:order:'..voucherId
--3.脚本业务
--3.1判断库存是否充足
local stock = tonumber(redis.call('GET',stockKey))
if(not stock or stock <= 0) then
    return 1
end
--3.2判断用户是否下单
if(redis.call('SISMEMBER',orderKey,userId) == 1) then
    return 2
end
--3.3扣库存
redis.call('INCRBY',stockKey,-1)
--3.4保存用户
redis.call('SADD',orderKey,userId)
--发送信息到消息队列
redis.call('XADD','stream.orders','*','voucherId',voucherId,'userId',userId,'id',orderId)
return 0