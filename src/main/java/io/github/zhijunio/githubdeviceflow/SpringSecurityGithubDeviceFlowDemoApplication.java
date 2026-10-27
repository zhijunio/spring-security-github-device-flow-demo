package io.github.zhijunio.githubdeviceflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class SpringSecurityGithubDeviceFlowDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringSecurityGithubDeviceFlowDemoApplication.class, args);
    }
}
