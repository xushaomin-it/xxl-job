xxl-job 在web管理界面上为我们提供了任务的高级配置,包括调度器集群下的路由策略,任务调度过期策略, 任务阻塞处理策略


### 1. 路由策略
在Xxl-job web管理界面, 新建一个任务, 可以选择的路由策略有以下几种
![image.png](https://cdn.nlark.com/yuque/0/2021/png/8380065/1614246670063-47134575-f56a-4e2d-9da9-458c2f987cc9.png#align=left&display=inline&height=854&margin=%5Bobject%20Object%5D&name=image.png&originHeight=854&originWidth=1436&size=95718&status=done&style=none&width=1436)
![image.png](https://cdn.nlark.com/yuque/0/2021/png/8380065/1614262004748-37067511-de2f-493d-b537-5c78de90531d.png#align=left&display=inline&height=563&margin=%5Bobject%20Object%5D&name=image.png&originHeight=563&originWidth=492&size=26389&status=done&style=none&width=492)

- ExecutorRouter: 路由策略抽象
- ExecutorRouteStrategyEnum: 通过枚举的方式,把路由key和策略实现类进行了聚合
```java
// 第一个
    FIRST(I18nUtil.getString("jobconf_route_first"), new ExecutorRouteFirst()),
    // 最后一个
    LAST(I18nUtil.getString("jobconf_route_last"), new ExecutorRouteLast()),
    // 轮询
    ROUND(I18nUtil.getString("jobconf_route_round"), new ExecutorRouteRound()),
    // 随机
    RANDOM(I18nUtil.getString("jobconf_route_random"), new ExecutorRouteRandom()),
    // 一致性hash
    CONSISTENT_HASH(I18nUtil.getString("jobconf_route_consistenthash"), new ExecutorRouteConsistentHash()),
    // 最少使用
    LEAST_FREQUENTLY_USED(I18nUtil.getString("jobconf_route_lfu"), new ExecutorRouteLFU()),
    // 最久未使用
    LEAST_RECENTLY_USED(I18nUtil.getString("jobconf_route_lru"), new ExecutorRouteLRU()),
    // 故障转移
    FAILOVER(I18nUtil.getString("jobconf_route_failover"), new ExecutorRouteFailover()),
    // 忙碌转移
    BUSYOVER(I18nUtil.getString("jobconf_route_busyover"), new ExecutorRouteBusyover()),
    // 分片广播
    SHARDING_BROADCAST(I18nUtil.getString("jobconf_route_shard"), null);
```

- stategy包下: 不同路由策略实现

源码入口
com.xxl.job.admin.core.thread.JobTriggerPoolHelper#trigger
  -> com.xxl.job.admin.core.thread.JobTriggerPoolHelper#addTrigger
   -> com.xxl.job.admin.core.trigger.XxlJobTrigger#trigger
    -> com.xxl.job.admin.core.trigger.XxlJobTrigger#processTrigger


com.xxl.job.admin.core.trigger.XxlJobTrigger#trigger方法
```java
 public static void trigger(int jobId,
                               TriggerTypeEnum triggerType,
                               int failRetryCount,
                               String executorShardingParam,
                               String executorParam,
                               String addressList) {

        // load data 通过jobId从数据库中查询该任务的具体信息
        XxlJobInfo jobInfo = XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().loadById(jobId);
        if (jobInfo == null) {
            logger.warn(">>>>>>>>>>>> trigger fail, jobId invalid，jobId={}", jobId);
            return;
        }
        // 设置执行参数
        if (executorParam != null) {
            jobInfo.setExecutorParam(executorParam);
        }
        // 设置失败重试次数
        int finalFailRetryCount = failRetryCount>=0?failRetryCount:jobInfo.getExecutorFailRetryCount();
        // 获取该任务的执行器信息
        XxlJobGroup group = XxlJobAdminConfig.getAdminConfig().getXxlJobGroupDao().load(jobInfo.getJobGroup());

        // cover addressList, 如果有手动录入地址, 那么覆盖执行器地址
        if (addressList!=null && addressList.trim().length()>0) {
            group.setAddressType(1);
            group.setAddressList(addressList.trim());
        }

        // sharding param 分片信息
        int[] shardingParam = null;
        if (executorShardingParam!=null){
            String[] shardingArr = executorShardingParam.split("/");
            if (shardingArr.length==2 && isNumeric(shardingArr[0]) && isNumeric(shardingArr[1])) {
                shardingParam = new int[2];
                shardingParam[0] = Integer.valueOf(shardingArr[0]);
                shardingParam[1] = Integer.valueOf(shardingArr[1]);
            }
        }
        // 广播模式or分片模式,循环执行器配置的服务地址列表
        if (ExecutorRouteStrategyEnum.SHARDING_BROADCAST==ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null)
                && group.getRegistryList()!=null && !group.getRegistryList().isEmpty()
                && shardingParam==null) {
            for (int i = 0; i < group.getRegistryList().size(); i++) {
                // 遍历调用所有的执行器,执行器集群列表中当前执行器的序号(i)以及执行器总数量(group.getRegistryList().size())
                processTrigger(group, jobInfo, finalFailRetryCount, triggerType, i, group.getRegistryList().size());
            }
        } else {
            if (shardingParam == null) {
                shardingParam = new int[]{0, 1};
            }
            // 非广播模式进入
            processTrigger(group, jobInfo, finalFailRetryCount, triggerType, shardingParam[0], shardingParam[1]);
        }

    }

```
分片广播模式没有单独采用路由策略模板实现,而是当系统判断当前任务的路由策略是分片广播时,就会遍历执行器的集群机器列表,给每一个机器都发送执行消息,分片总数为集群机器数量,分片标记从0开始.
如果非分片广播模式,则调用processTrigger方法
```java
private static void processTrigger(XxlJobGroup group, XxlJobInfo jobInfo, int finalFailRetryCount, TriggerTypeEnum triggerType, int index, int total){

        // param
        // 阻塞策略(单机串行 / 丢弃后续调度 / 覆盖之前调度)
        ExecutorBlockStrategyEnum blockStrategy = ExecutorBlockStrategyEnum.match(jobInfo.getExecutorBlockStrategy(), ExecutorBlockStrategyEnum.SERIAL_EXECUTION);  // block strategy
        // 路由策略, 官方一共有10种路由策略
        ExecutorRouteStrategyEnum executorRouteStrategyEnum = ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null);    // route strategy
        String shardingParam = (ExecutorRouteStrategyEnum.SHARDING_BROADCAST==executorRouteStrategyEnum)?String.valueOf(index).concat("/").concat(String.valueOf(total)):null;

        ...初始化调度日志信息并保存,初始化调度请求参数triggerParam

        // 3、init address 选择执行器服务地址
        String address = null;
        ReturnT<String> routeAddressResult = null;
        if (group.getRegistryList()!=null && !group.getRegistryList().isEmpty()) {
            if (ExecutorRouteStrategyEnum.SHARDING_BROADCAST == executorRouteStrategyEnum) {
                if (index < group.getRegistryList().size()) {
                    address = group.getRegistryList().get(index);
                } else {
                    address = group.getRegistryList().get(0);
                }
            } else {
                // 根据路由策略选择合适的执行器服务地址执行任务 (设计模式->策略模式)
                routeAddressResult = executorRouteStrategyEnum.getRouter().route(triggerParam, group.getRegistryList());
                if (routeAddressResult.getCode() == ReturnT.SUCCESS_CODE) {
                    address = routeAddressResult.getContent();
                }
            }
        } else {
            routeAddressResult = new ReturnT<String>(ReturnT.FAIL_CODE, I18nUtil.getString("jobconf_trigger_address_empty"));
        }

        // 4、trigger remote executor
        ReturnT<String> triggerResult = null;
        if (address != null) {
            // 远程调用,执行调度任务
            triggerResult = runExecutor(triggerParam, address);
        } else {
            triggerResult = new ReturnT<String>(ReturnT.FAIL_CODE, null);
        }

        // 5、collection trigger info
    }
```
通过routeAddressResult = executorRouteStrategyEnum.getRouter().route(triggerParam, group.getRegistryList()); 获取到调用的执行器地址

1. ExecutorRouteBusyover: 忙碌转移

遍历调用所有在线的执行器/idleBeat方法,判断执行器是否处于执行当前任务的状态,如果是,则继续调用,直到获取到一台空闲的执行器
```java
/**
 * Created by xuxueli on 17/3/10.
 * 忙碌转移策略:
 * 遍历所有在线的执行器,遍历请求执行器接口判断执行器是否忙碌(执行器任务线程是否处于执行状态)
 */
public class ExecutorRouteBusyover extends ExecutorRouter {

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {
        StringBuffer idleBeatResultSB = new StringBuffer();
        for (String address : addressList) {
            // beat
            ReturnT<String> idleBeatResult = null;
            try {
                // 遍历所有执行器,请求执行器接口判断执行器是否忙碌(是否有任务正在执行)
                ExecutorBiz executorBiz = XxlJobScheduler.getExecutorBiz(address);
                idleBeatResult = executorBiz.idleBeat(new IdleBeatParam(triggerParam.getJobId()));
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                idleBeatResult = new ReturnT<String>(ReturnT.FAIL_CODE, ""+e );
            }
            idleBeatResultSB.append( (idleBeatResultSB.length()>0)?"<br><br>":"")
                    .append(I18nUtil.getString("jobconf_idleBeat") + "：")
                    .append("<br>address：").append(address)
                    .append("<br>code：").append(idleBeatResult.getCode())
                    .append("<br>msg：").append(idleBeatResult.getMsg());

            // beat success
            if (idleBeatResult.getCode() == ReturnT.SUCCESS_CODE) {
                idleBeatResult.setMsg(idleBeatResultSB.toString());
                idleBeatResult.setContent(address);
                return idleBeatResult;
            }
        }
        return new ReturnT<String>(ReturnT.FAIL_CODE, idleBeatResultSB.toString());
    }
}
```

2. ExecutorRouteFailover: 故障转移

遍历集群地址列表并调用执行器/idle方法,如果调用成功则跳出循环,返回成功信息
```java
public class ExecutorRouteFailover extends ExecutorRouter {

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {

        StringBuffer beatResultSB = new StringBuffer();
        for (String address : addressList) { // 故障转移策略, 轮询所有执行器, 判断如果执行器没有宕机则直接返回该执行器地址
            // beat
            ReturnT<String> beatResult = null;
            try {
                ExecutorBiz executorBiz = XxlJobScheduler.getExecutorBiz(address);
                beatResult = executorBiz.beat();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                beatResult = new ReturnT<String>(ReturnT.FAIL_CODE, ""+e );
            }
            beatResultSB.append( (beatResultSB.length()>0)?"<br><br>":"")
                    .append(I18nUtil.getString("jobconf_beat") + "：")
                    .append("<br>address：").append(address)
                    .append("<br>code：").append(beatResult.getCode())
                    .append("<br>msg：").append(beatResult.getMsg());

            // beat success
            if (beatResult.getCode() == ReturnT.SUCCESS_CODE) {

                beatResult.setMsg(beatResultSB.toString());
                beatResult.setContent(address);
                return beatResult;
            }
        }
        return new ReturnT<String>(ReturnT.FAIL_CODE, beatResultSB.toString());

    }
}
```

3. ExecutorRouteFirst: 第一个

获取注册在线执行器列表中的第一个
```java
public class ExecutorRouteFirst extends ExecutorRouter {

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList){
        return new ReturnT<String>(addressList.get(0));
    }

}
```

4. ExecutorRouteLast: 最后一个

获取注册在线执行器列表中的第一个
```java
public class ExecutorRouteLast extends ExecutorRouter {

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {
        return new ReturnT<String>(addressList.get(addressList.size()-1));
    }
}
```

5. ExecutorRouteLFU: 最不经常使用

单个JOb对应的每个执行器,使用频率最低的优先被选举
通过一个HashMap存储每个任务对应的机器执行次数,然后通过map value排序,得到执行次数最小的哪个,也就是得到了最不经常使用的那台机器
```java
public class ExecutorRouteLFU extends ExecutorRouter {

    private static ConcurrentMap<Integer, HashMap<String, Integer>> jobLfuMap = new ConcurrentHashMap<Integer, HashMap<String, Integer>>();
    private static long CACHE_VALID_TIME = 0;

    public String route(int jobId, List<String> addressList) {

        // cache clear 缓存时间 一天
        if (System.currentTimeMillis() > CACHE_VALID_TIME) {
            jobLfuMap.clear();
            CACHE_VALID_TIME = System.currentTimeMillis() + 1000*60*60*24;
        }

        // lfu item init
        HashMap<String, Integer> lfuItemMap = jobLfuMap.get(jobId);     // Key排序可以用TreeMap+构造入参Compare；Value排序暂时只能通过ArrayList；
        if (lfuItemMap == null) {
            lfuItemMap = new HashMap<String, Integer>();
            jobLfuMap.putIfAbsent(jobId, lfuItemMap);   // 避免重复覆盖
        }

        // put new
        for (String address: addressList) {
            if (!lfuItemMap.containsKey(address) || lfuItemMap.get(address) >1000000 ) {
                lfuItemMap.put(address, new Random().nextInt(addressList.size()));  // 初始化时主动Random一次，缓解首次压力
            }
        }
        // remove old
        List<String> delKeys = new ArrayList<>();
        for (String existKey: lfuItemMap.keySet()) {
            if (!addressList.contains(existKey)) {
                delKeys.add(existKey);
            }
        }
        if (delKeys.size() > 0) {
            for (String delKey: delKeys) {
                lfuItemMap.remove(delKey);
            }
        }

        // load least userd count address
        List<Map.Entry<String, Integer>> lfuItemList = new ArrayList<Map.Entry<String, Integer>>(lfuItemMap.entrySet());
        Collections.sort(lfuItemList, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> o1, Map.Entry<String, Integer> o2) {
                return o1.getValue().compareTo(o2.getValue());
            }
        });

        Map.Entry<String, Integer> addressItem = lfuItemList.get(0);
        String minAddress = addressItem.getKey();
        addressItem.setValue(addressItem.getValue() + 1);

        return addressItem.getKey();
    }

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {
        String address = route(triggerParam.getJobId(), addressList);
        return new ReturnT<String>(address);
    }

}

```

6. ExecutorRouteLRU: 最近最久未使用

单个job对应的每个执行器,最久未使用的优先被选举
通过LinkedHashMap来实现LRU算法,通过linkedHashMap的每次get/put的时候会进行排序,最新操作的数据会在最后面,从而取第一个数据就代表最久没有被使用的

7. ExecutorRouteRandom: 随机策略

直接通过random随机选取一台机器执行任务
```java
public class ExecutorRouteRandom extends ExecutorRouter {

    private static Random localRandom = new Random();

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {
        String address = addressList.get(localRandom.nextInt(addressList.size()));
        return new ReturnT<String>(address);
    }

}
```

8. ExecutorRouteRound: 轮询策略

通过一个ConcurrentHashMap记录每个任务对应的执行次数,维护一个count值,通过count值对集群机器大小取余得选取执行机器
```java
public class ExecutorRouteRound extends ExecutorRouter {

    private static ConcurrentMap<Integer, AtomicInteger> routeCountEachJob = new ConcurrentHashMap<>();
    private static long CACHE_VALID_TIME = 0;

    private static int count(int jobId) {
        // cache clear
        if (System.currentTimeMillis() > CACHE_VALID_TIME) {
            routeCountEachJob.clear();
            CACHE_VALID_TIME = System.currentTimeMillis() + 1000*60*60*24;
        }

        AtomicInteger count = routeCountEachJob.get(jobId);
        if (count == null || count.get() > 1000000) {
            // 初始化时主动Random一次，缓解首次压力
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
        // 假设现在 执行器集群 3, 当前任务总数3, 那么当前执行器执行总数就是所有数据的总和
        String address = addressList.get(count(triggerParam.getJobId())%addressList.size());
        return new ReturnT<String>(address);
    }
}
```
### 2. 调度过期策略
![image.png](https://cdn.nlark.com/yuque/0/2021/png/8380065/1614246818417-a5f7c8e1-7854-4446-811c-aaffe4d9b216.png#align=left&display=inline&height=774&margin=%5Bobject%20Object%5D&name=image.png&originHeight=774&originWidth=905&size=49256&status=done&style=none&width=905)
Xxl-Job调度过期策略有两种

- 过期忽略执行
- 过期立即执行一次

其实现在调度中心调度任务核心代码中
com.xxl.job.admin.core.thread.JobScheduleHelper#start方法中
```java
if (scheduleList!=null && scheduleList.size()>0) {
    // 2、push time-ring
    for (XxlJobInfo jobInfo: scheduleList) {

        // time-ring jump
        if (nowTime > jobInfo.getTriggerNextTime() + PRE_READ_MS) {
             // 如果调度任务超过当前时间5s, 则直接放弃执行
             // 2.1、trigger-expire > 5s：pass && make next-trigger-time
             logger.warn(">>>>>>>>>>> xxl-job, schedule misfire, jobId = " + jobInfo.getId());

             // 1、misfire match 获取当前任务的调度策略
             MisfireStrategyEnum misfireStrategyEnum = MisfireStrategyEnum.match(jobInfo.getMisfireStrategy(), MisfireStrategyEnum.DO_NOTHING);
             if (MisfireStrategyEnum.FIRE_ONCE_NOW == misfireStrategyEnum) { // 如果这个任务的超时处理利策略是立即执行一次,那么直接执行
                  // FIRE_ONCE_NOW 》
                  JobTriggerPoolHelper.trigger(jobInfo.getId(), TriggerTypeEnum.MISFIRE, -1, null, null, null);
                  logger.debug(">>>>>>>>>>> xxl-job, schedule push trigger : jobId = " + jobInfo.getId() );
                  }

                  // 2、fresh next, 设置该调度任务下一次执行的时间(通过corn表达式计算下一次执行的时间)
                 refreshNextValidTime(jobInfo, new Date());
```
从xxl_job_info表中取出最近5s内即将要执行的任务,如果当前任务已超过当前时间5s后,说明该任务调度超时,则判断任务的调度超时处理策略是否是过期立即执行一次,如果是,则执行该调度任务.否则,跳过该任务并更新该任务的下次执行时间.
### 3. 阻塞处理策略
![image.png](https://cdn.nlark.com/yuque/0/2021/png/8380065/1614246857738-d97459e9-7ff3-4965-ba9e-8f05a972765e.png#align=left&display=inline&height=771&margin=%5Bobject%20Object%5D&name=image.png&originHeight=771&originWidth=905&size=53612&status=done&style=none&width=905)
Xxl-job的任务阻塞处理策略有三种

- 单机串行
- 丢弃后续调度
- 覆盖之前调度

阻塞处理策略的源码实现是在执行器模块中实现,在执行器接收到调度中心任务调度执行请求处理逻辑中
源码入口
com.xxl.job.core.biz.ExecutorBiz#run
 -> com.xxl.job.core.biz.impl.ExecutorBizImpl#run
```java
public ReturnT<String> run(TriggerParam triggerParam) {
        ...
        // executor block strategy 执行阻塞处理策略
        if (jobThread != null) {
            ExecutorBlockStrategyEnum blockStrategy = ExecutorBlockStrategyEnum.match(triggerParam.getExecutorBlockStrategy(), null);
            if (ExecutorBlockStrategyEnum.DISCARD_LATER == blockStrategy) { // 丢弃后续调度
                // discard when running, 如果当前任务线程正在执行任务, 那么直接返回调度失败
                if (jobThread.isRunningOrHasQueue()) {
                    return new ReturnT<String>(ReturnT.FAIL_CODE, "block strategy effect："+ExecutorBlockStrategyEnum.DISCARD_LATER.getTitle());
                }
            } else if (ExecutorBlockStrategyEnum.COVER_EARLY == blockStrategy) { // 覆盖之前调度
                // kill running jobThread, 如果当前任务线程正在执行任务,那么将任务线程kill掉, 然后新建一条新的任务线程执行任务
                if (jobThread.isRunningOrHasQueue()) {
                    removeOldReason = "block strategy effect：" + ExecutorBlockStrategyEnum.COVER_EARLY.getTitle();

                    jobThread = null;
                }
            } else { // 单机串行
                // just queue trigger
            }
        }

        // replace thread (new or exists invalid)
        if (jobThread == null) {
            jobThread = XxlJobExecutor.registJobThread(triggerParam.getJobId(), jobHandler, removeOldReason);
        }

        // push data to queue 将任务放入队列中,由线程从队列中获取任务执行
        ReturnT<String> pushResult = jobThread.pushTriggerQueue(triggerParam);
        return pushResult;
    }
```
在执行器执行任务时, 从调度参数triggerParam中获取到当前任务的阻塞执行策略

- 丢弃后续调度: 判断任务线程是否处于空闲,如果处于空闲,则执行任务,非空闲状态则抛弃当前下发的任务

获取到执行当前任务的jobThread, 判断jobThread是否处在执行任务中或者 triggerQueue 任务是否存在任务
```java
public boolean isRunningOrHasQueue() {
        return running || triggerQueue.size()>0;
}
```

- 覆盖之前调度: 直接将当前任务线程kill掉(把任务线程正在跑的任务以及任务队列中的任务都丢弃), 然后另起一条任务线程跑当前任务
