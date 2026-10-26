package io.github.zhijunio.githubdeviceflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("github.device-flow")
public record GitHubDeviceFlowProperties(String clientId, String scope) {

    public boolean configured() {
        return clientId != null && !clientId.isBlank();
    }
}
