package com.atguigu;

import java.io.IOException;
import java.util.List;

import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

/*
 * 1. 秒杀，要求一个用户最多只能秒杀成功一次！
 * 
 * 2. 分析：   
 * 				商品库存：  key： prodid
 * 						value： String
 * 
 *				秒杀成功的用户名单：  key: users
 *								value： set
 *
 * 3. 流程：
 * 				① 判断当前用户是否已经秒杀成功过
 * 						sismembers()
 * 							如果已经秒杀成功
 * 
 * 				② 用户没用秒杀过，执行秒杀流程
 * 						判断商品的库存状态！
 * 							a) null  : 活动尚未开始，商家没用上架商品！
 * 							b) >0: 可以秒杀。
 * 									每次秒杀库存自减1；
 * 									将用户添加到已经成功的名单中！
 * 							c) <=0: 返回false
 * 
 * 4. 使用连接池来获取Jedis连接
 * 
 * 5. 使用一个压力测试工具，模拟高并发环境
 * 			ab(apache benchmark)： centos自带！
 * 					ab [args] url
 * 					-n : 一共要模拟的请求总数
 * 					-c : 同时发送的请求数（并发数）
 * 					-p ： 如果是post请求，需要将参数编写在一个文件中提交
 * 							格式： 参数名=参数值&
 * 					-T ： 如果发送post请求，需要指定-T为
 * 							application/x-www-form-urlencoded
 * 
 * 6. 超卖问题：  使用java的悲观锁是可以解决的！
 * 		               可以使用redis的乐观锁解决！ 
 * 					在进行库存减1操作之前，必须 为库存加锁！  watch(key)
 * 						将整个秒杀操作，建立事务（组队）！ 执行事务中的所有命令！在执行之前，redis会检查当前上锁的
 * 						资源是否被改变，如果改变，取消执行！
 * 			加锁的时间：  对key进行读写操作之前，加锁！
 * 
 * 7. 如何解决秒杀的公平性：  java是多线程的，在一个线程中，有多条redis命令的话！不同的线程的快慢，影响其他线程执行的效果！
 * 						多个线程，每个线程都将多条redis命令，组合成一条命令！
 * 						redis服务，每次只能处理一条命令，只能处理完一个线程的所有工作后，才会继续处理其他线程的工作！
 * 
 * 		使用Lua脚本： 可以在脚本本编写多条 redis命令，1个脚本提交给server后，会一次性全部执行！
 * 						一个lua脚本是一个原子！
 * 						
 */
public class SecKill_redis {

	private static final org.slf4j.Logger logger = LoggerFactory.getLogger(SecKill_redis.class);

	// 秒杀的实际方法
	public static boolean doSecKill(String uid, String prodid) throws IOException {
		
		
			
			// ①需要判断用户是否已经秒杀过
			JedisPool jedisPool = JedisPoolUtil.getJedisPoolInstance();
			
			Jedis jedis = jedisPool.getResource();
			
			// 生成产品的key
			String productKey = "sk:" + prodid + ":product";
			String userKey = "sk:" + prodid + ":user";
			
			if (jedis.sismember(userKey, uid)) {
				
				System.out.println(uid + "===》已经秒杀成功过了！");
				
				jedis.close();
				
				return false;
				
			};
			
			// 在读写之前加锁
			jedis.watch(productKey);
			
			String product_store = jedis.get(productKey);
			
			if (product_store == null) {
				
				System.out.println("商家尚未初始化商品！");
				
				jedis.close();
				
				return false;
			}
			
			int store = Integer.parseInt(product_store);
			
			if (store<=0) {
				
				System.out.println(prodid+"===>已经被秒杀完了！");
				
				jedis.close();
				
				return false;
			}
			
			//==================== 真正进入秒杀================
			
			Transaction transaction = jedis.multi();
			
			// 库存自减1
			transaction.decr(productKey);
			// 存储秒杀成功的用户
			transaction.sadd(userKey, uid);
			// result中只会返回执行成功的结果
			List<Object> result = transaction.exec();
			
			if (result.size() !=2 || result == null) {
				
				System.out.println(uid+"===>秒杀失败！");
				
				jedis.close();
				
				return false;
			}
			
			System.out.println(uid+"===>秒杀成功！");
			
			jedis.close();
			
			return true;
		}


}
