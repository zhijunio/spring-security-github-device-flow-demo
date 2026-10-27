package io.github.zhijunio.githubdeviceflow.config;

import io.github.zhijunio.githubdeviceflow.github.DeviceCodeOAuth2AuthorizedClientProvider;
import io.github.zhijunio.githubdeviceflow.github.GitHubDeviceFlowClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
public class OAuth2ClientConfiguration {

    public static final String REGISTRATION_ID = "github";
    public static final AuthorizationGrantType DEVICE_CODE_GRANT =
            new AuthorizationGrantType("urn:ietf:params:oauth:grant-type:device_code");

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(GitHubDeviceFlowProperties properties) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(properties.clientId().isBlank() ? "unconfigured" : properties.clientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(DEVICE_CODE_GRANT)
                .scope("read:user")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userNameAttributeName("id")
                .clientName("GitHub")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    OAuth2AuthorizedClientService oauth2AuthorizedClientService(ClientRegistrationRepository registrations) {
        return new InMemoryOAuth2AuthorizedClientService(registrations);
    }

    @Bean
    OAuth2AuthorizedClientRepository oauth2AuthorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    OAuth2AuthorizedClientProvider deviceCodeAuthorizedClientProvider(
            GitHubDeviceFlowClient gitHubDeviceFlowClient, Clock clock) {
        return new DeviceCodeOAuth2AuthorizedClientProvider(gitHubDeviceFlowClient, clock);
    }

    @Bean
    OAuth2AuthorizedClientManager oauth2AuthorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService authorizedClientService,
            OAuth2AuthorizedClientProvider deviceCodeAuthorizedClientProvider) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClientService);
        manager.setAuthorizedClientProvider(deviceCodeAuthorizedClientProvider);
        manager.setContextAttributesMapper(OAuth2AuthorizeRequest::getAttributes);
        return manager;
    }

    @Bean
    DefaultOAuth2UserService githubOAuth2UserService() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.setInterceptors(List.of((request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.ACCEPT, "application/vnd.github+json");
            request.getHeaders().set(HttpHeaders.USER_AGENT, "spring-security-github-device-flow-demo");
            request.getHeaders().set("X-GitHub-Api-Version", "2022-11-28");
            return execution.execute(request, body);
        }));

        DefaultOAuth2UserService userService = new DefaultOAuth2UserService();
        userService.setRestOperations(restTemplate);
        return userService;
    }
}
