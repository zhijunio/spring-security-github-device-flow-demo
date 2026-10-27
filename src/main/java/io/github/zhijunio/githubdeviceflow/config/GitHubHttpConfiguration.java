package io.github.zhijunio.githubdeviceflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class GitHubHttpConfiguration {

    @Bean
    RestClient gitHubOAuthClient(RestClient.Builder builder) {
        return builder.baseUrl("https://github.com")
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("User-Agent", "spring-security-github-device-flow-demo")
                .build();
    }
}
