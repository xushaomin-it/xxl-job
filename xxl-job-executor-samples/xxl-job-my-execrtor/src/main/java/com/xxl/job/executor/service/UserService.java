package com.xxl.job.executor.service;

import org.springframework.stereotype.Service;

/**
 * @author xsm
 * @date 2021/2/27
 * @Description
 */
@Service
public class UserService {

    // GLUE 模式 注入的bean

    public String getById(Integer id){
        return "xxl-job";
    }
}
