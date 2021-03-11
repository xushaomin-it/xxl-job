- 源码版本 2.3.0 版本
### 1. 阅读Xxl-job源码之前的一些思考

- 定时触发任务是如何实现的
- 调度中心集群环境下,如何保证任务不会被重复调度



### 调度中心初始化
**com.xxl.job.admin.core.conf.XxlJobAdminConfig**
通过实现InitializingBean, DisposableBean 对AdminConfig进行bean生命周期管理
```java
@Component
public class XxlJobAdminConfig implements InitializingBean, DisposableBean {

    private static XxlJobAdminConfig adminConfig = null;
    public static XxlJobAdminConfig getAdminConfig() {
        return adminConfig;
    }


    // ---------------------- XxlJobScheduler ----------------------

    private XxlJobScheduler xxlJobScheduler;

    @Override
    public void afterPropertiesSet() throws Exception {
        adminConfig = this;

        xxlJobScheduler = new XxlJobScheduler();
        xxlJobScheduler.init();
    }

    @Override
    public void destroy() throws Exception {
        xxlJobScheduler.destroy();
    }
    ... 
}
```
核心方法 xxlJobScheduler.init()
```java
public void init() throws Exception {
        // init i18n, 初始化国际化
        initI18n();

        // admin trigger pool start 初始化两个触发调度线程, 分为 快, 慢两个线程池
        JobTriggerPoolHelper.toStart();

        // admin registry monitor run
        // 初始化用于维护xxl_job_group表中自动注册类型执行器的address_list地址
        JobRegistryHelper.getInstance().start();

        // admin fail-monitor run, 初始化monitorThread线程,用于处理异常任务并进行重试
        JobFailMonitorHelper.getInstance().start();

        // admin lose-monitor run ( depend on JobTriggerPoolHelper )
        // 初始化了callbackThreadPool线程池用于处理执行器的任务执行结果回调, 初始化monitorThread用于处理异常的任务并进行重试
        JobCompleteHelper.getInstance().start();

        // admin log report start
        // 初始化logThread线程,对调度日志进行统计和清理
        JobLogReportHelper.getInstance().start();

        // start-schedule  ( depend on JobTriggerPoolHelper )
        // JobScheduleHelper
        // 真正开始任务调度的地方,开启scheduleThread线程
        // 调度器，死循环，在xxl_job_info表里取将要执行的任务，更新下次执行时间的，调用JobTriggerPoolHelper类，来给执行器发送调度任务的 重点
        JobScheduleHelper.getInstance().start();

        logger.info(">>>>>>>>> init xxl-job admin success.");
    }
```
在init()方法中,主要做了以下几件事

1. JobTriggerPoolHelper.toStart();

初始化了两个触发调度线程池,分为快,慢两个线程池, Xxl-job调度中心在触发调度时会判断任务是否为慢任务,1分钟内超过10次耗时超过500ms,则将任务放入慢任务线程池进行处理,避免快任务线程池资源耗尽

2. JobRegistryHelper.getInstance().start();
- 初始化了维护调度中心注册/下线通知线程池 registryOrRemoveThreadPool

      用于执行器上报注册信息及下线移除注册信息

- 维护自动注册类型执行器的address_list地址线程 registryMonitorThread

      每30s轮训数据库,将xxl_job_registry表中已下线(超过90s没有心跳)的执行器移除,并同步维护xxl_job_group(执行器注册表)每个执行器对应的地址列表

3. JobFailMonitorHelper.getInstance().start() 

初始化monitorThread线程,用于处理异常任务并进行任务失败重试.
monitorThread线程会每隔10s从xxl_job_log表中查询出最多1000条的失败记录,然后判断该事变记录重试次数大于0,如果是,那么则进行任务重试. 然后判断该失败任务是否需要进行告警处理

4. JobLogReportHelper._getInstance_().start()

初始化logThread线程, 对调度日志进行统计处理方便web页面报表数据展示,以及日志清理

5. JobScheduleHelper._getInstance_().start();真正开始执行调度任务的地方,重点关注这个方法



### 任务触发调度
```java
public void start(){

        // schedule thread
        scheduleThread = new Thread(new Runnable() {
            @Override
            public void run() {

                try {
                    // 5s 后的整秒数,当前毫秒 % 1000 是区域得到下次整秒的时间距离
                    TimeUnit.MILLISECONDS.sleep(5000 - System.currentTimeMillis()%1000 );
                } catch (InterruptedException e) {
                    if (!scheduleThreadToStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                logger.info(">>>>>>>>> init xxl-job admin scheduler success.");

                // pre-read count: treadpool-size * trigger-qps (each trigger cost 50ms, qps = 1000/50 = 20), 设置获取查询下一次执行的任务的总数量
                int preReadCount = (XxlJobAdminConfig.getAdminConfig().getTriggerPoolFastMax() + XxlJobAdminConfig.getAdminConfig().getTriggerPoolSlowMax()) * 20;

                while (!scheduleThreadToStop) {

                    // Scan Job
                    long start = System.currentTimeMillis();

                    Connection conn = null;
                    Boolean connAutoCommit = null;
                    PreparedStatement preparedStatement = null;

                    boolean preReadSuc = true;
                    try {
                        // 通过MySQL的悲观锁(for update)实现分布式锁,保证调度中心集群下只有一个调度中心能够获取到执行任务
                        conn = XxlJobAdminConfig.getAdminConfig().getDataSource().getConnection();
                        connAutoCommit = conn.getAutoCommit();
                        conn.setAutoCommit(false);

                        preparedStatement = conn.prepareStatement(  "select * from xxl_job_lock where lock_name = 'schedule_lock' for update" );
                        preparedStatement.execute();

                        // tx start

                        // 1、pre read
                        long nowTime = System.currentTimeMillis();
                        // 查询获取到接下来5s内即将要执行的任务, trigger_status(调度状态) = 1(运行) and trigger_next_time(下次调度时间)
                        List<XxlJobInfo> scheduleList = XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleJobQuery(nowTime + PRE_READ_MS, preReadCount);
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

                                } else if (nowTime > jobInfo.getTriggerNextTime()) {
                                    // 2.2、trigger-expire < 5s：direct-trigger && make next-trigger-time
                                    // 如果调度任务超过当前时间, 且在5s内, 则直接执行调度任务
                                    // 1、trigger
                                    JobTriggerPoolHelper.trigger(jobInfo.getId(), TriggerTypeEnum.CRON, -1, null, null, null);
                                    logger.debug(">>>>>>>>>>> xxl-job, schedule push trigger : jobId = " + jobInfo.getId() );

                                    // 2、fresh next, 设置该调度任务下一次执行的时间
                                    refreshNextValidTime(jobInfo, new Date());

                                    // next-trigger-time in 5s, pre-read again
                                    if (jobInfo.getTriggerStatus()==1 && nowTime + PRE_READ_MS > jobInfo.getTriggerNextTime()) {
                                        // 如果当前调度任务的下一次执行时间是在5s内, 那么则直接放入到时间轮中
                                        // 1、make ring second
                                        int ringSecond = (int)((jobInfo.getTriggerNextTime()/1000)%60);

                                        // 2、push time ring 放入到时间轮中
                                        pushTimeRing(ringSecond, jobInfo.getId());

                                        // 3、fresh next 刷新该调度任务下一次执行时间
                                        // 在这个任务被加入时间轮前 这个任务的下次执行时间就已经被刷新了,也就是下一次的时间
                                        // 在这里再次刷新一次,这个下次执行时间(本轮调度后的第二次执行时间)会被记录到数据库中
                                        // 这样时间轮里面记录的是本轮调度后的下一次调度时间,更新到数据库的是时间轮调度后的下一次时间
                                        // 下次循环数据库扫描出来这个任务,也是时间轮调度后的下一次,不会处在重复执行的可能
                                        refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));

                                    }

                                } else {
                                    // 2.3、trigger-pre-read：time-ring trigger && make next-trigger-time

                                    // 1、make ring second, 计算时间, 取余
                                    int ringSecond = (int)((jobInfo.getTriggerNextTime()/1000)%60);

                                    // 2、push time ring 放入到时间轮中
                                    pushTimeRing(ringSecond, jobInfo.getId());

                                    // 3、fresh next, 刷新该调度任务下一次执行的时间
                                    refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));

                                }

                            }

                            // 3、update trigger info
                            for (XxlJobInfo jobInfo: scheduleList) {
                                // 更新调度任务下一次执行的时间
                                XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleUpdate(jobInfo);
                            }

                        } else {
                            preReadSuc = false;
                        }

                        // tx stop


                    } catch (Exception e) {
                        if (!scheduleThreadToStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread error:{}", e);
                        }
                    } finally {

                        // commit
                        if (conn != null) {
                            try {
                                conn.commit();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                            try {
                                conn.setAutoCommit(connAutoCommit);
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                            try {
                                conn.close();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                        }

                        // close PreparedStatement
                        if (null != preparedStatement) {
                            try {
                                preparedStatement.close();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                        }
                    }
                    long cost = System.currentTimeMillis()-start;


                    // Wait seconds, align second
                    if (cost < 1000) {  // scan-overtime, not wait 如果本次耗时 < 1 s
                        try {
                            // pre-read period: success > scan each second; fail > skip this period; 对齐时间,保证每1s执行一次
                            // 如果preReadSuc为true表示本次有任务调度, sleep到下一次整秒
                            // 如果preReadSuc为false表示本次没有任务调度, sleep到第5个整秒
                            TimeUnit.MILLISECONDS.sleep((preReadSuc?1000:PRE_READ_MS) - System.currentTimeMillis()%1000);
                        } catch (InterruptedException e) {
                            if (!scheduleThreadToStop) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                    }

                }

                logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread stop");
            }
        });
        scheduleThread.setDaemon(true);
        scheduleThread.setName("xxl-job, admin JobScheduleHelper#scheduleThread");
        scheduleThread.start();


        // ring thread
        // 处理时间轮中存放的定时任务
        ringThread = new Thread(new Runnable() {
            @Override
            public void run() {

                while (!ringThreadToStop) {

                    // align second 对齐时间
                    try {
                        TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis() % 1000);
                    } catch (InterruptedException e) {
                        if (!ringThreadToStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }

                    try {
                        // second data
                        List<Integer> ringItemData = new ArrayList<>();
                        // 当前秒数
                        int nowSecond = Calendar.getInstance().get(Calendar.SECOND);
                        // 避免处理耗时太长，跨过刻度，向前校验一个刻度；
                        for (int i = 0; i < 2; i++) { // 取当前秒数和前1秒数, 保证时间轮中的每个bucket都能够被处理到
                            List<Integer> tmpData = ringData.remove( (nowSecond+60-i)%60 );
                            if (tmpData != null) {
                                ringItemData.addAll(tmpData);
                            }
                        }

                        // ring trigger
                        logger.debug(">>>>>>>>>>> xxl-job, time-ring beat : " + nowSecond + " = " + Arrays.asList(ringItemData) );
                        if (ringItemData.size() > 0) {
                            // do trigger
                            for (int jobId: ringItemData) {
                                // do trigger, 执行调度任务
                                JobTriggerPoolHelper.trigger(jobId, TriggerTypeEnum.CRON, -1, null, null, null);
                            }
                            // clear
                            ringItemData.clear();
                        }
                    } catch (Exception e) {
                        if (!ringThreadToStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread error:{}", e);
                        }
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread stop");
            }
        });
        ringThread.setDaemon(true);
        ringThread.setName("xxl-job, admin JobScheduleHelper#ringThread");
        ringThread.start();
    }
```
JobScheduleHelper._getInstance_().start()  方法主要是开启了两条线程

- scheduleThread 线程: 不断从db中获取即将要执行的任务,然后进行任务调度
- ringThread 线程: 从时间轮中获取即将要执行的任务,进行任务调度



##### scheduleThread线程执行流程

1. 在调度器集群环境下,为保证调度任务只允许被一台调度器调度的情况下,通过MySQL的悲观锁, select xx for update来实现
```java
// 通过MySQL的悲观锁(for update)实现分布式锁,保证调度中心集群下只有一个调度中心能够获取到执行任务
                        conn = XxlJobAdminConfig.getAdminConfig().getDataSource().getConnection();
                        connAutoCommit = conn.getAutoCommit();
                        conn.setAutoCommit(false);

                        preparedStatement = conn.prepareStatement(  "select * from xxl_job_lock where lock_name = 'schedule_lock' for update" );
                        preparedStatement.execute();
```

2. 轮询db,查询找到xxl_job_info表下次触发时间(trigger_next_time)在距当前时间5秒内的任务
2. 触发调度逻辑

  获取到接下来5s内即将执行的调度任务, 循环任务列表
  (1) 对达到当前时间且超出当前时间5秒外的任务, 即为调度超时, 判断调度超时是否立即执行一次,如果是, 执行调度, 再计算任务下一次调度的时间
  (2) 对达到当前时间且超出当前时间5秒内的任务,立即执行触发逻辑; 如果该任务的下一次触发时间是在5s内,则放到时间轮内,再计算任务的下下次触发调度时间
  (3) 对未达到当前时间的任务: 直接放到时间轮内, 计算任务下一次触发调度时间并更新
![定时触发流程.png](https://cdn.nlark.com/yuque/0/2021/png/8380065/1614322537662-d25c28a4-5b6e-4f3b-86cb-9011738fd555.png#align=left&display=inline&height=815&margin=%5Bobject%20Object%5D&name=%E5%AE%9A%E6%97%B6%E8%A7%A6%E5%8F%91%E6%B5%81%E7%A8%8B.png&originHeight=815&originWidth=1050&size=88540&status=done&style=none&width=1050)


![调度中心任务调度流程.png](https://cdn.nlark.com/yuque/0/2021/png/8380065/1614156588514-00f3275b-6063-40db-a711-7dd9268078bc.png#align=left&display=inline&height=671&margin=%5Bobject%20Object%5D&name=%E8%B0%83%E5%BA%A6%E4%B8%AD%E5%BF%83%E4%BB%BB%E5%8A%A1%E8%B0%83%E5%BA%A6%E6%B5%81%E7%A8%8B.png&originHeight=671&originWidth=890&size=81132&status=done&style=none&width=890)
##### ringThread 执行流程
每隔1s从ringData(时间轮)中获取当前秒以及前一秒执行的任务列表, 然后遍历任务列表执行任务调度操作
```java
 // ring thread
        // 处理时间轮中存放的定时任务
        ringThread = new Thread(new Runnable() {
            @Override
            public void run() {

                while (!ringThreadToStop) {

                    // align second 对齐时间
                    try {
                        TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis() % 1000);
                    } catch (InterruptedException e) {
                        if (!ringThreadToStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }

                    try {
                        // second data
                        List<Integer> ringItemData = new ArrayList<>();
                        // 当前秒数
                        int nowSecond = Calendar.getInstance().get(Calendar.SECOND);
                        // 避免处理耗时太长，跨过刻度，向前校验一个刻度；
                        for (int i = 0; i < 2; i++) { // 取当前秒数和前1秒数, 保证时间轮中的每个bucket都能够被处理到
                            List<Integer> tmpData = ringData.remove( (nowSecond+60-i)%60 );
                            if (tmpData != null) {
                                ringItemData.addAll(tmpData);
                            }
                        }

                        // ring trigger
                        logger.debug(">>>>>>>>>>> xxl-job, time-ring beat : " + nowSecond + " = " + Arrays.asList(ringItemData) );
                        if (ringItemData.size() > 0) {
                            // do trigger
                            for (int jobId: ringItemData) {
                                // do trigger, 执行调度任务
                                JobTriggerPoolHelper.trigger(jobId, TriggerTypeEnum.CRON, -1, null, null, null);
                            }
                            // clear
                            ringItemData.clear();
                        }
                    } catch (Exception e) {
                        if (!ringThreadToStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread error:{}", e);
                        }
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread stop");
            }
        });
        ringThread.setDaemon(true);
        ringThread.setName("xxl-job, admin JobScheduleHelper#ringThread");
        ringThread.start();
    }
```
ringData 为 一个 key 为计算触发时间获取的秒数, value 为任务id列表 的Map, 可以看做是一个时间轮
![image.png](https://cdn.nlark.com/yuque/0/2021/png/8380065/1614393227914-3eda7d25-6a98-4ed9-a3b1-95dde7b4bbb3.png#align=left&display=inline&height=628&margin=%5Bobject%20Object%5D&name=image.png&originHeight=628&originWidth=1463&size=143713&status=done&style=none&width=1463)
增加时间轮处理的目的: 任务过多可能会延迟,为了保障触发时间尽可能和任务设置的触发时间尽量一致,把即将要触发的任务提前放到时间轮丽,每秒来触发时间轮相应节点的任务.




