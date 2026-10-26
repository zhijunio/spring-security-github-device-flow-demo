package io.github.zhijunio.githubdeviceflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class GitHubHttpConfiguration {

    @Bean
    RestClient gitHubOAuthClient(RestClient.Builder builder) {
        return builder.baseUrl("https://github.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "spring-security-github-device-flow-demo")
                .build();
    }

    @Bean
    RestClient gitHubApiClient(RestClient.Builder builder) {
        return builder.baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "spring-security-github-device-flow-demo")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }
}
