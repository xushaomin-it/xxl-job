package com.xxl.job.executor.service.jobhandler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.util.DateUtil;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * @author xsm
 * @Date 2021/2/21 15:10
 */
@Component
public class MyJobHandler {

    /**
     * 1. 简单测试
     * @throws Exception
     */
//    @XxlJob("demoJobHandler")
    public void demoJobHandler() throws Exception {
        XxlJobHelper.log("XXL-JOB, Hello World.");
        System.out.println("time: " + DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
        System.out.println("XXL-JOB, Hello World");
    }

    /**
     * 2、分片广播任务
     */
    @XxlJob("shardingJobHandler")
    public void shardingJobHandler() throws Exception {

        // 分片参数
        int shardIndex = XxlJobHelper.getShardIndex(); // 当前分片序号(从0开始), 执行器集群列表中当前执行器的序号
        int shardTotal = XxlJobHelper.getShardTotal(); // 总分片数,执行器集群的总机器数量

        XxlJobHelper.log("分片参数：当前分片序号 = {}, 总分片数 = {}", shardIndex, shardTotal);

        // 业务逻辑
        for (int i = 0; i < shardTotal; i++) {
            if (i == shardIndex) {
                XxlJobHelper.log("第 {} 片, 命中分片开始处理", i);
                System.out.println("第 " + i + " 片, 命中分片开始处理");
            } else {
                XxlJobHelper.log("第 {} 片, 忽略", i);
            }
        }
    }

    /**
     * 3. 轮询
     */
    @XxlJob("routeRound")
    public void routeRound() throws Exception {
        System.out.println("xxl-job 轮询调用" + "time: " + DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 随机
     * @throws Exception
     */
    @XxlJob("routeRandom")
    public void routeRandom() throws Exception {
        System.out.println("xxl-job 随机调用" + "time: " + DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 固定速率
     * @throws Exception
     */
    @XxlJob("fixedRate")
    public void fixedRate() throws Exception {
        String jobParam = XxlJobHelper.getJobParam();
        System.out.println("xxl-job 固定速率, param: " + jobParam + "time: " + DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 忙碌转移
     * @throws Exception
     */
    @XxlJob("busyOver")
    public void busyOver() throws Exception {
        System.out.println("xxl-job 忙碌转移" + "time: " + DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
    }

}
