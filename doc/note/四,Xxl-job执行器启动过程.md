#### 阅读执行器源码前的一些思考
- 任务执行器注册中心是如何实现的
- 如何实现任务执行器的路由
- 如何实现任务执行结果回调



#### 1. 执行器启动过程
配置执行器
```java
 @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        logger.info(">>>>>>>>>>> xxl-job config init.");
        XxlJobSpringExecutor xxlJobSpringExecutor = new XxlJobSpringExecutor();
        xxlJobSpringExecutor.setAdminAddresses(adminAddresses);
        xxlJobSpringExecutor.setAppname(appname);
        xxlJobSpringExecutor.setAddress(address);
        xxlJobSpringExecutor.setIp(ip);
        xxlJobSpringExecutor.setPort(port);
        xxlJobSpringExecutor.setAccessToken(accessToken);
        xxlJobSpringExecutor.setLogPath(logPath);
        xxlJobSpringExecutor.setLogRetentionDays(logRetentionDays);

        return xxlJobSpringExecutor;
    }
```
com.xxl.job.core.executor.impl.XxlJobSpringExecutor spring环境下的执行器启动类
```java
public class XxlJobSpringExecutor extends XxlJobExecutor implements ApplicationContextAware, SmartInitializingSingleton, DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(XxlJobSpringExecutor.class);


    // start
    @Override
    public void afterSingletonsInstantiated() {
        
        // init JobHandler Repository (for method), 注册 jobhandler, 基于java method
        initJobHandlerMethodRepository(applicationContext);

        // refresh GlueFactory
        GlueFactory.refreshInstance(1);

        // super start
        try {
            super.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

```
initJobHandlerMethodRepository(applicationContext)方法主要目的是获取所有@XxlJob修饰的方法,然后注册为一个jobHandler(任务执行器).
```java
 private void initJobHandlerMethodRepository(ApplicationContext applicationContext) {
        if (applicationContext == null) {
            return;
        }
        // init job handler from method
        String[] beanDefinitionNames = applicationContext.getBeanNamesForType(Object.class, false, true); // 获取到所有beanDefinition的名称
        for (String beanDefinitionName : beanDefinitionNames) {
            Object bean = applicationContext.getBean(beanDefinitionName); // 根据beanDefinition名称获取对应的bean
            // 所有贴有@XxlJob注解的方法, 基于spring bean获取, 所以 @XxlJob 修饰的方法一定要是spring bean
            Map<Method, XxlJob> annotatedMethods = null;   // referred to ：org.springframework.context.event.EventListenerMethodProcessor.processBean
            try {
                annotatedMethods = MethodIntrospector.selectMethods(bean.getClass(),
                        new MethodIntrospector.MetadataLookup<XxlJob>() {
                            @Override
                            public XxlJob inspect(Method method) {
                                return AnnotatedElementUtils.findMergedAnnotation(method, XxlJob.class);
                            }
                        });
            } catch (Throwable ex) {
                logger.error("xxl-job method-jobhandler resolve error for bean[" + beanDefinitionName + "].", ex);
            }
            if (annotatedMethods==null || annotatedMethods.isEmpty()) {
                continue;
            }

            for (Map.Entry<Method, XxlJob> methodXxlJobEntry : annotatedMethods.entrySet()) {
                Method executeMethod = methodXxlJobEntry.getKey();
                XxlJob xxlJob = methodXxlJobEntry.getValue();
                if (xxlJob == null) {
                    continue;
                }

                String name = xxlJob.value();
                if (name.trim().length() == 0) {
                    throw new RuntimeException("xxl-job method-jobhandler name invalid, for[" + bean.getClass() + "#" + executeMethod.getName() + "] .");
                }
                if (loadJobHandler(name) != null) { // 校验 jobhandler name 是否重复
                    throw new RuntimeException("xxl-job jobhandler[" + name + "] naming conflicts.");
                }
                executeMethod.setAccessible(true);
                // init and destory 是否有 init, destroy 方法
                Method initMethod = null;
                Method destroyMethod = null;

                if (xxlJob.init().trim().length() > 0) {
                    try {
                        initMethod = bean.getClass().getDeclaredMethod(xxlJob.init());
                        initMethod.setAccessible(true);
                    } catch (NoSuchMethodException e) {
                        throw new RuntimeException("xxl-job method-jobhandler initMethod invalid, for[" + bean.getClass() + "#" + executeMethod.getName() + "] .");
                    }
                }
                if (xxlJob.destroy().trim().length() > 0) {
                    try {
                        destroyMethod = bean.getClass().getDeclaredMethod(xxlJob.destroy());
                        destroyMethod.setAccessible(true);
                    } catch (NoSuchMethodException e) {
                        throw new RuntimeException("xxl-job method-jobhandler destroyMethod invalid, for[" + bean.getClass() + "#" + executeMethod.getName() + "] .");
                    }
                }
                // registry jobhandler 注册 jobhandler, 存放于一个 jobhandlerName -> methodJobHandler 的map中
                registJobHandler(name, new MethodJobHandler(bean, executeMethod, initMethod, destroyMethod));
            }
        }

    }
```
com.xxl.job.core.executor.XxlJobExecutor#start
```java
public void start() throws Exception {

        // init logpath 初始化日志目录路径
        XxlJobFileAppender.initLogPath(logPath);

        // init invoker, admin-client 初始化adminBizList, 方便后续调度中心注册自己
        initAdminBizList(adminAddresses, accessToken);

        // init JobLogFileCleanThread 初始化日志清洗线程
        JobLogFileCleanThread.getInstance().start(logRetentionDays);

        // init TriggerCallbackThread, 初始化调度任务回调线程, 向调度中心回报任务执行结果
        TriggerCallbackThread.getInstance().start();

        // init executor-server 初始化执行器, 重点
        // 1. 创建netty服务端, 2. 执行器向调度中心注册自己, 同时开启心跳检测线程
        initEmbedServer(address, ip, port, appname, accessToken);
    }
```
com.xxl.job.core.executor.XxlJobExecutor#initEmbedServer
```java
private void initEmbedServer(String address, String ip, int port, String appname, String accessToken) throws Exception {

        // fill ip port
        port = port>0?port: NetUtil.findAvailablePort(9999);
        ip = (ip!=null&&ip.trim().length()>0)?ip: IpUtil.getIp();

        // generate address
        if (address==null || address.trim().length()==0) {
            String ip_port_address = IpUtil.getIpPort(ip, port);   // registry-address：default use address to registry , otherwise use ip:port if address is null
            address = "http://{ip_port}/".replace("{ip_port}", ip_port_address);
        }

        // accessToken
        if (accessToken==null || accessToken.trim().length()==0) {
            logger.warn(">>>>>>>>>>> xxl-job accessToken is empty. To ensure system security, please set the accessToken.");
        }

        // start 创建netty服务端
        embedServer = new EmbedServer();
        embedServer.start(address, port, appname, accessToken);
    }
```
com.xxl.job.core.server.EmbedServer#start
创建 netty server, 同时将执行器注册到调度中心
```java
public void start(final String address, final int port, final String appname, final String accessToken) {
        executorBiz = new ExecutorBizImpl();
        thread = new Thread(new Runnable() {

            @Override
            public void run() {

                // param
                EventLoopGroup bossGroup = new NioEventLoopGroup();
                EventLoopGroup workerGroup = new NioEventLoopGroup();
                ThreadPoolExecutor bizThreadPool = new ThreadPoolExecutor(
                        0,
                        200,
                        60L,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<Runnable>(2000),
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable r) {
                                return new Thread(r, "xxl-rpc, EmbedServer bizThreadPool-" + r.hashCode());
                            }
                        },
                        new RejectedExecutionHandler() {
                            @Override
                            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                                throw new RuntimeException("xxl-job, EmbedServer bizThreadPool is EXHAUSTED!");
                            }
                        });


                try {
                    // start server
                    ServerBootstrap bootstrap = new ServerBootstrap();
                    bootstrap.group(bossGroup, workerGroup)
                            .channel(NioServerSocketChannel.class)
                            .childHandler(new ChannelInitializer<SocketChannel>() {
                                @Override
                                public void initChannel(SocketChannel channel) throws Exception {
                                    channel.pipeline()
                                            // 长连接
                                            .addLast(new IdleStateHandler(0, 0, 30 * 3, TimeUnit.SECONDS))  // beat 3N, close if idle
                                            // http协议解码器
                                            .addLast(new HttpServerCodec())
                                            .addLast(new HttpObjectAggregator(5 * 1024 * 1024))  // merge request & reponse to FULL
                                            // 接受请求处理器, 调度中心调度请求通过该handler进行处理
                                            .addLast(new EmbedHttpServerHandler(executorBiz, accessToken, bizThreadPool));
                                }
                            })
                            .childOption(ChannelOption.SO_KEEPALIVE, true);

                    // bind
                    ChannelFuture future = bootstrap.bind(port).sync();

                    logger.info(">>>>>>>>>>> xxl-job remoting server start success, nettype = {}, port = {}", EmbedServer.class, port);

                    // start registry 任务执行器向调度中心注册自己
                    startRegistry(appname, address);

                    // wait util stop 监听Netty进程销毁，避免代码块执行完，进程自己销毁
                    future.channel().closeFuture().sync();

                } catch (InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        logger.info(">>>>>>>>>>> xxl-job remoting server stop.");
                    } else {
                        logger.error(">>>>>>>>>>> xxl-job remoting server error.", e);
                    }
                } finally {
                    // stop netty优雅关闭
                    try {
                        workerGroup.shutdownGracefully();
                        bossGroup.shutdownGracefully();
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                }

            }

        });
        thread.setDaemon(true);	// daemon, service jvm, user thread leave >>> daemon leave >>> jvm leave
        thread.start();
    }
```
总结:
执行器的启动过程
![执行器启动流程.png](https://cdn.nlark.com/yuque/0/2021/png/8380065/1614669071449-1b287c20-bb40-436c-b0c1-68a7b072e8b5.png#align=left&display=inline&height=778&margin=%5Bobject%20Object%5D&name=%E6%89%A7%E8%A1%8C%E5%99%A8%E5%90%AF%E5%8A%A8%E6%B5%81%E7%A8%8B.png&originHeight=778&originWidth=1143&size=67850&status=done&style=none&width=1143)


#### 2. 执行器自动注册
com.xxl.job.core.executor.impl.XxlJobSpringExecutor#afterSingletonsInstantiated
-> com.xxl.job.core.executor.XxlJobExecutor#start
 -> com.xxl.job.core.executor.XxlJobExecutor#initEmbedServer 初始化netty服务端
  -> com.xxl.job.core.server.EmbedServer#start
   -> com.xxl.job.core.server.EmbedServer#startRegistry 执行器注册到调度中心
    -> com.xxl.job.core.thread.ExecutorRegistryThread#start
```java
public void start(final String appname, final String address){

        // valid
        if (appname==null || appname.trim().length()==0) {
            logger.warn(">>>>>>>>>>> xxl-job, executor registry config fail, appname is null.");
            return;
        }
        if (XxlJobExecutor.getAdminBizList() == null) {
            logger.warn(">>>>>>>>>>> xxl-job, executor registry config fail, adminAddresses is null.");
            return;
        }

        registryThread = new Thread(new Runnable() {
            @Override
            public void run() {

                // registry
                while (!toStop) {
                    try {
                        RegistryParam registryParam = new RegistryParam(RegistryConfig.RegistType.EXECUTOR.name(), appname, address);
                        for (AdminBiz adminBiz: XxlJobExecutor.getAdminBizList()) {
                            try {
                                ReturnT<String> registryResult = adminBiz.registry(registryParam);
                                if (registryResult!=null && ReturnT.SUCCESS_CODE == registryResult.getCode()) {
                                    registryResult = ReturnT.SUCCESS;
                                    logger.debug(">>>>>>>>>>> xxl-job registry success, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                                    break;
                                } else {
                                    logger.info(">>>>>>>>>>> xxl-job registry fail, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                                }
                            } catch (Exception e) {
                                logger.info(">>>>>>>>>>> xxl-job registry error, registryParam:{}", registryParam, e);
                            }

                        }
                    } catch (Exception e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }

                    }

                    try {
                        if (!toStop) {
                            TimeUnit.SECONDS.sleep(RegistryConfig.BEAT_TIMEOUT);
                        }
                    } catch (InterruptedException e) {
                        if (!toStop) {
                            logger.warn(">>>>>>>>>>> xxl-job, executor registry thread interrupted, error msg:{}", e.getMessage());
                        }
                    }
                }

                // registry remove
                try {
                    RegistryParam registryParam = new RegistryParam(RegistryConfig.RegistType.EXECUTOR.name(), appname, address);
                    for (AdminBiz adminBiz: XxlJobExecutor.getAdminBizList()) {
                        try {
                            ReturnT<String> registryResult = adminBiz.registryRemove(registryParam);
                            if (registryResult!=null && ReturnT.SUCCESS_CODE == registryResult.getCode()) {
                                registryResult = ReturnT.SUCCESS;
                                logger.info(">>>>>>>>>>> xxl-job registry-remove success, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                                break;
                            } else {
                                logger.info(">>>>>>>>>>> xxl-job registry-remove fail, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                            }
                        } catch (Exception e) {
                            if (!toStop) {
                                logger.info(">>>>>>>>>>> xxl-job registry-remove error, registryParam:{}", registryParam, e);
                            }

                        }

                    }
                } catch (Exception e) {
                    if (!toStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, executor registry thread destory.");

            }
        });
        registryThread.setDaemon(true);
        registryThread.setName("xxl-job, executor ExecutorRegistryThread");
        registryThread.start();
    }
```
在执行器启动的时候,xxl执行器会开启一条自动注册线程,向调度中心集群中的每一个调度中心注册自己(只需要向一个调度中心注册成功即可), 同时每隔30s向调度中心注册(心跳检测).
![执行器注册和心跳处理.png](https://cdn.nlark.com/yuque/0/2021/png/8380065/1615298023336-9944c2c9-1a75-484d-9950-3337bd1af0fd.png#align=left&display=inline&height=390&margin=%5Bobject%20Object%5D&name=%E6%89%A7%E8%A1%8C%E5%99%A8%E6%B3%A8%E5%86%8C%E5%92%8C%E5%BF%83%E8%B7%B3%E5%A4%84%E7%90%86.png&originHeight=390&originWidth=656&size=26363&status=done&style=none&width=656)


#### 3. 执行器任务执行及结果回调
在执行器启动时,会自动创建一个netty服务端用于接受调度中心的http请求(包括任务下发,执行日志请求,心跳检测,终止任务执行等)
com.xxl.job.core.server.EmbedServer
-> com.xxl.job.core.server.EmbedServer.EmbedHttpServerHandler
 -> com.xxl.job.core.server.EmbedServer.EmbedHttpServerHandler#process 用于处理调度中心的请求
```java
 // services mapping
            try {
                if ("/beat".equals(uri)) {
                    return executorBiz.beat();
                } else if ("/idleBeat".equals(uri)) {
                    IdleBeatParam idleBeatParam = GsonTool.fromJson(requestData, IdleBeatParam.class);
                    return executorBiz.idleBeat(idleBeatParam);
                } else if ("/run".equals(uri)) {
                    // 执行任务
                    TriggerParam triggerParam = GsonTool.fromJson(requestData, TriggerParam.class);
                    return executorBiz.run(triggerParam);
                } else if ("/kill".equals(uri)) {
                    KillParam killParam = GsonTool.fromJson(requestData, KillParam.class);
                    return executorBiz.kill(killParam);
                } else if ("/log".equals(uri)) {
                    LogParam logParam = GsonTool.fromJson(requestData, LogParam.class);
                    return executorBiz.log(logParam);
                } else {
                    return new ReturnT<String>(ReturnT.FAIL_CODE, "invalid request, uri-mapping("+ uri +") not found.");
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                return new ReturnT<String>(ReturnT.FAIL_CODE, "request error:" + ThrowableUtil.toString(e));
            }
```
其中/run方法用于处理任务下发请求
com.xxl.job.core.biz.ExecutorBiz#run
com.xxl.job.core.biz.impl.ExecutorBizImpl#run
```java
public ReturnT<String> run(TriggerParam triggerParam) {
        // load old：jobHandler + jobThread
        JobThread jobThread = XxlJobExecutor.loadJobThread(triggerParam.getJobId());
        IJobHandler jobHandler = jobThread!=null?jobThread.getHandler():null;
        String removeOldReason = null;

        // valid：jobHandler + jobThread
        GlueTypeEnum glueTypeEnum = GlueTypeEnum.match(triggerParam.getGlueType());
        if (GlueTypeEnum.BEAN == glueTypeEnum) {

            // new jobhandler
            IJobHandler newJobHandler = XxlJobExecutor.loadJobHandler(triggerParam.getExecutorHandler());

            // valid old jobThread
            if (jobThread!=null && jobHandler != newJobHandler) {
                // change handler, need kill old thread
                removeOldReason = "change jobhandler or glue type, and terminate the old job thread.";

                jobThread = null;
                jobHandler = null;
            }

            // valid handler
            if (jobHandler == null) {
                jobHandler = newJobHandler;
                if (jobHandler == null) {
                    return new ReturnT<String>(ReturnT.FAIL_CODE, "job handler [" + triggerParam.getExecutorHandler() + "] not found.");
                }
            }

        } else if (GlueTypeEnum.GLUE_GROOVY == glueTypeEnum) {

            // valid old jobThread
            if (jobThread != null &&
                    !(jobThread.getHandler() instanceof GlueJobHandler
                        && ((GlueJobHandler) jobThread.getHandler()).getGlueUpdatetime()==triggerParam.getGlueUpdatetime() )) {
                // change handler or gluesource updated, need kill old thread
                removeOldReason = "change job source or glue type, and terminate the old job thread.";

                jobThread = null;
                jobHandler = null;
            }

            // valid handler
            if (jobHandler == null) {
                try {
                    IJobHandler originJobHandler = GlueFactory.getInstance().loadNewInstance(triggerParam.getGlueSource());
                    jobHandler = new GlueJobHandler(originJobHandler, triggerParam.getGlueUpdatetime());
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    return new ReturnT<String>(ReturnT.FAIL_CODE, e.getMessage());
                }
            }
        } else if (glueTypeEnum!=null && glueTypeEnum.isScript()) {

            // valid old jobThread
            if (jobThread != null &&
                    !(jobThread.getHandler() instanceof ScriptJobHandler
                            && ((ScriptJobHandler) jobThread.getHandler()).getGlueUpdatetime()==triggerParam.getGlueUpdatetime() )) {
                // change script or gluesource updated, need kill old thread
                removeOldReason = "change job source or glue type, and terminate the old job thread.";

                jobThread = null;
                jobHandler = null;
            }

            // valid handler
            if (jobHandler == null) {
                jobHandler = new ScriptJobHandler(triggerParam.getJobId(), triggerParam.getGlueUpdatetime(), triggerParam.getGlueSource(), GlueTypeEnum.match(triggerParam.getGlueType()));
            }
        } else {
            return new ReturnT<String>(ReturnT.FAIL_CODE, "glueType[" + triggerParam.getGlueType() + "] is not valid.");
        }

        // executor block strategy
        if (jobThread != null) {
            ExecutorBlockStrategyEnum blockStrategy = ExecutorBlockStrategyEnum.match(triggerParam.getExecutorBlockStrategy(), null);
            if (ExecutorBlockStrategyEnum.DISCARD_LATER == blockStrategy) {
                // discard when running
                if (jobThread.isRunningOrHasQueue()) {
                    return new ReturnT<String>(ReturnT.FAIL_CODE, "block strategy effect："+ExecutorBlockStrategyEnum.DISCARD_LATER.getTitle());
                }
            } else if (ExecutorBlockStrategyEnum.COVER_EARLY == blockStrategy) {
                // kill running jobThread
                if (jobThread.isRunningOrHasQueue()) {
                    removeOldReason = "block strategy effect：" + ExecutorBlockStrategyEnum.COVER_EARLY.getTitle();

                    jobThread = null;
                }
            } else {
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
pushTirggerQueue方法 将调度任务push到队列中,将netty业务线程和具体消费线程解耦,防止netty处理不过来长时间阻塞
```java
public ReturnT<String> pushTriggerQueue(TriggerParam triggerParam) {
		// avoid repeat 判断是否任务被重复下发
		if (triggerLogIdSet.contains(triggerParam.getLogId())) {
			logger.info(">>>>>>>>>>> repeate trigger job, logId:{}", triggerParam.getLogId());
			return new ReturnT<String>(ReturnT.FAIL_CODE, "repeate trigger job, logId:" + triggerParam.getLogId());
		}

		triggerLogIdSet.add(triggerParam.getLogId());
		triggerQueue.add(triggerParam);
        return ReturnT.SUCCESS;
	}
```
任务线程从triggerQueue队列获取任务执行,同时将任务执行参数放入回调队列中
```java
// callback trigger request in queue
		while(triggerQueue !=null && triggerQueue.size()>0){
			TriggerParam triggerParam = triggerQueue.poll();
			if (triggerParam!=null) {
				// is killed
				TriggerCallbackThread.pushCallBack(new HandleCallbackParam(
						triggerParam.getLogId(),
						triggerParam.getLogDateTime(),
						XxlJobContext.HANDLE_COCE_FAIL,
						stopReason + " [job not executed, in the job queue, killed.]")
				);
			}
		}
```
com.xxl.job.core.thread.TriggerCallbackThread 任务回调线程
从callBackQueue队列中获取回调任务参数,调用调度中心上报任务执行结果, 最后记录任务上报结果日志
```java
 HandleCallbackParam callback = getInstance().callBackQueue.take();
                        if (callback != null) {

                            // callback list param
                            List<HandleCallbackParam> callbackParamList = new ArrayList<HandleCallbackParam>();
                            int drainToNum = getInstance().callBackQueue.drainTo(callbackParamList);
                            callbackParamList.add(callback);

                            // callback, will retry if error
                            if (callbackParamList!=null && callbackParamList.size()>0) {
                                doCallback(callbackParamList);
                            }
                        }
```
```java
 private void doCallback(List<HandleCallbackParam> callbackParamList){
        boolean callbackRet = false;
        // callback, will retry if error
        for (AdminBiz adminBiz: XxlJobExecutor.getAdminBizList()) {
            try {
                // 调用调度中心任务回调接口,上报任务执行结果
                ReturnT<String> callbackResult = adminBiz.callback(callbackParamList);
                if (callbackResult!=null && ReturnT.SUCCESS_CODE == callbackResult.getCode()) {
                    // 记录任务上报日志
                    callbackLog(callbackParamList, "<br>----------- xxl-job job callback finish.");
                    callbackRet = true;
                    break;
                } else {
                    // 记录任务上报日志
                    callbackLog(callbackParamList, "<br>----------- xxl-job job callback fail, callbackResult:" + callbackResult);
                }
            } catch (Exception e) {
                callbackLog(callbackParamList, "<br>----------- xxl-job job callback error, errorMsg:" + e.getMessage());
            }
        }
        if (!callbackRet) {
            appendFailCallbackFile(callbackParamList);
        }
    }
```
总结: 任务执行结回调过程
![执行器任务回调.png](https://cdn.nlark.com/yuque/0/2021/png/8380065/1615297173690-b0cfb7ae-4238-4de2-9e2d-fb5580e12e1c.png#align=left&display=inline&height=623&margin=%5Bobject%20Object%5D&name=%E6%89%A7%E8%A1%8C%E5%99%A8%E4%BB%BB%E5%8A%A1%E5%9B%9E%E8%B0%83.png&originHeight=623&originWidth=1406&size=55713&status=done&style=none&width=1406)


