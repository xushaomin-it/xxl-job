package com.xxl.job.admin.core.route.strategy;

import com.xxl.job.admin.core.route.ExecutorRouter;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.biz.model.TriggerParam;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by xuxueli on 17/3/10.
 * 轮询策略
 * 获取到该任务的所有执行次数 % 执行器列表总数
 * 假设现在 执行器集群 3, 当前任务总数3, 那么当前执行器执行总数就是所有数据的总和
 */
public class ExecutorRouteRound extends ExecutorRouter {

    private static ConcurrentMap<Integer, AtomicInteger> routeCountEachJob = new ConcurrentHashMap<>();
    private static long CACHE_VALID_TIME = 0;

    private static int count(int jobId) {
        // cache clear
        // 如果当前的时间，大于缓存的时间，那么说明需要刷新了
        if (System.currentTimeMillis() > CACHE_VALID_TIME) {
            routeCountEachJob.clear();
            // 设置缓存时间戳，默认缓存一天，一天之后会从新开始
            CACHE_VALID_TIME = System.currentTimeMillis() + 1000*60*60*24;
        }

        AtomicInteger count = routeCountEachJob.get(jobId);
        if (count == null || count.get() > 1000000) {
            // 当 count==null或者count大于100万的时候，系统会默认在100之间随机一个数字 ， 放入hashMap, 然后返回该数字
            // 初始化时主动Random一次，缓解首次压力
            // 为啥首次需要随机一次，而不是指定第一台呢？
            // 因为如果默认指定第一台的话，那么所有任务的首次加载全部会到第一台执行器上面去，这样会导致第一台机器刚开始的时候压力很大。
            count = new AtomicInteger(new Random().nextInt(100));
        } else {
            // count++
            count.addAndGet(1);
        }
        routeCountEachJob.put(jobId, count);
        return count.get();
    }

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {
        // 轮询算法, 获取到该任务的所有执行次数 % 执行器列表总数
        // 在执行器地址列表，获取相应的地址，  通过count(jobid) 这个方法来实现，主要逻辑在这个方法
        // 通过count（jobId）拿到数字之后， 通过求于的方式，拿到执行器地址
        // 例： count=2 , addresslist.size = 3
        // 2%3 = 2 ,  则拿list中下表为2的地址
        String address = addressList.get(count(triggerParam.getJobId())%addressList.size());
        return new ReturnT<String>(address);
    }

}
