package com.xxl.job.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author xsm
 * @Date 2021/2/21 15:05
 */
@EnableScheduling
@SpringBootApplication
public class MyXxlJobExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyXxlJobExecutorApplication.class, args);
    }
}
