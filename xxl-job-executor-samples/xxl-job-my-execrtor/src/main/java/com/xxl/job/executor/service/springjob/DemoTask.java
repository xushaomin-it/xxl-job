package com.xxl.job.executor.service.springjob;

import com.xxl.job.core.util.DateUtil;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * @author xsm
 * @date 2021/3/3
 * @Description
 */
@Component
public class DemoTask {

    @Scheduled(cron = "0/5 * * * * ?")
    public void task(){
        String time = DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss");
        System.out.println("spring scheduling,time: " + time);
    }
}
