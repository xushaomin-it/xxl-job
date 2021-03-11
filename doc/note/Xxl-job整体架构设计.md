### 1. 目前项目中使用的Spring @Schedule 定时任务的优缺点
1. 优点
- Spring自带的定时任务框架使用简单,拆箱即用




2. 缺点
- 不支持集群: 为避免集群环境重复执行任务,需要额外设置分布式锁处理
- 不支持任务生命周期统一管理: 不重启服务情况下关闭,启动任务
- 不支持分片任务: 处理有序数据时,多机器分片执行任务处理不同数据
- 不支持动态调整: 不重启服务的情况下修改任务参数
- 无报警机制: 任务失败之后没有报警机制
- 不支持失败重试: 出现异常后任务终止,不能根据执行状态控制任务重新执行
- 任务数据统计: 在任务数据量大时,没有任务统计大盘统计执行执行情况



### 2. Xxl-job特性

- 简单: 支持Web页面对任务进行CRUD操作,操作简单
- 动态: 支持动态修改任务状态,启动/停止任务,终止运行中任务
- 调度中心,执行器HA
- 丰富的路由策略
- 故障转移
- 任务失败告警
- 分片广播
- 任务执行日志,运行报表

      ...
### 3. 整体架构
![](https://cdn.nlark.com/yuque/0/2021/png/8380065/1613914604186-ff79cea5-ab25-4b2d-b178-f230d57bf247.png#align=left&display=inline&height=616&margin=%5Bobject%20Object%5D&originHeight=616&originWidth=1153&size=0&status=done&style=none&width=1153)
将调度行为抽象形成调度中心公共平台, 而平台自身并不承担业务逻辑,调度中心负责发起调度请求.
将任务抽象成分散的JobHandler,交由"执行器"统一管理,"执行器"负责接受调度请求并执行对应的JobHandler中业务逻辑.
因此"调度"和"任务"两部分可以互相解耦,提供系统稳定性和扩展性.


调度中心

- 负责管理调度信息, 按照调度配置发出调度请求, 自身不承担业务代码
- 调度系统与任务解耦, 提高了系统的可用性和稳定性,同时调度系统性能不再受限于任务模块
- 支持可视化, 简单且动态的管理调度信息, 包括任务新建,更新,删除,GLUE开发和任务报警等
- 支持监控调度结果以及执行日志,支持执行器Failover

执行器

- 负责接收调度请求并执行任务逻辑.任务模块专注于任务的执行等操作,开发和维护更加简单和高效
- 接收调度中心的执行请求, 终止请求和日志请求等



### 4. 工作原理
![](https://cdn.nlark.com/yuque/0/2021/png/8380065/1613914731786-555bf1ec-d4cd-4d47-9ab3-673f3c27ae2c.png#align=left&display=inline&height=614&margin=%5Bobject%20Object%5D&originHeight=614&originWidth=963&size=0&status=done&style=none&width=963)


1. 任务执行器根据配置的调度中心的地址,自动注册到调度中心
1. 达到任务触发条件, 调度中心下发任务
1. 执行器基于线程池执行任务,并把执行结果放入内存队列中, 把执行日志写入到日志文件中
1. 执行器的回调线程消费内存队列中的执行结果,主动上报给调度中心
1. 当用户在调度中心查看任务日志, 调度中心请求任务执行器,任务执行器读取任务日志文件并返回日志详情



### 5. 高可用设计
#### 5.1 调度中心高可用设计
调度中心支持多节点部署,基于数据库行锁,保证触发器的名称和执行器时间相同,有且仅有一个调度中心节点去下发任务给执行器
核心代码 com.xxl.job.admin.core.thread.JobScheduleHelper#start
```java
public void start(){

        // schedule thread
        scheduleThread = new Thread(new Runnable() {
            ...
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
        ...
    }
```
#### 5.2 执行器高可用设计
执行器支持多节点部署,通过调度中心选择其中的执行器,下发任务来执行
路由策略

1. 忙碌转移(BUSYOVER): 当调度中心每次发起调度请求时,会按照顺序对执行器出空闲检测请求,第一个检测为空闲状态的执行器将被选定并发送调度请求
- 实现类: ExecutorRouteBusyover
2. 故障转移(FAILOVER): 当调度中心每次发起调度请求时,会按照顺序对执行器发出心跳检测请求,第一个检测为存活状态的执行器将会被选定并发送调度请求
- 实现类: ExecutorRouteFailover



### 6. 任务调度整体执行流程_源码层面
![任务触发执行总体流程.jpg](https://cdn.nlark.com/yuque/0/2021/jpeg/8380065/1614390232749-02761f32-24c9-40c9-a677-84daafba5d80.jpeg#align=left&display=inline&height=1435&margin=%5Bobject%20Object%5D&name=%E4%BB%BB%E5%8A%A1%E8%A7%A6%E5%8F%91%E6%89%A7%E8%A1%8C%E6%80%BB%E4%BD%93%E6%B5%81%E7%A8%8B.jpg&originHeight=1435&originWidth=1966&size=193519&status=done&style=none&width=1966)
