package net.hwyz.iov.cloud.edd.dict.service;


import lombok.extern.slf4j.Slf4j;
import net.hwyz.iov.cloud.framework.security.annotation.EnableCustomConfig;
import net.hwyz.iov.cloud.framework.security.annotation.EnableCustomFeignClients;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 启动类
 *
 * @author hwyz_leo
 */
@Slf4j
@EnableCustomConfig
@SpringBootApplication
@EnableDiscoveryClient
@EnableCustomFeignClients
public class DictApplication {

    public static void main(String[] args) {
        SpringApplication.run(DictApplication.class, args);
        log.info("应用启动完成");
    }

}


