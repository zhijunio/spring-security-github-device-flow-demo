package io.github.zhijunio.githubdeviceflow.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.zhijunio.githubdeviceflow.config.GitHubDeviceFlowProperties;
import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.security.Principal;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GitHubDeviceFlowClient {

    private static final String DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    private final RestClient oauthClient;
    private final RestClient apiClient;
    private final GitHubDeviceFlowProperties properties;

    public GitHubDeviceFlowClient(
            RestClient gitHubOAuthClient, RestClient gitHubApiClient, GitHubDeviceFlowProperties properties) {
        this.oauthClient = gitHubOAuthClient;
        this.apiClient = gitHubApiClient;
        this.properties = properties;
    }

    public DeviceAuthorization requestDeviceAuthorization() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("scope", properties.scope());

        DeviceAuthorization response = oauthPost("/login/device/code", form, DeviceAuthorization.class);
        if (response == null
                || isBlank(response.deviceCode())
                || isBlank(response.userCode())
                || response.expiresIn() == null
                || response.expiresIn() < 1
                || response.interval() == null
                || response.interval() < 1
                || !isGitHubVerificationUri(response.verificationUri())) {
            throw new GitHubProviderException("GitHub 返回的设备授权信息不完整。");
        }
        return response;
    }

    public TokenResponse pollAccessToken(String deviceCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("device_code", deviceCode);
        form.add("grant_type", DEVICE_GRANT_TYPE);
        return oauthPost("/login/oauth/access_token", form, TokenResponse.class);
    }

    public GitHubUser currentUser(String accessToken) {
        try {
            GitHubUser user = apiClient
                    .get()
                    .uri("/user")
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .body(GitHubUser.class);
            if (user == null || user.id() < 1 || isBlank(user.login())) {
                throw new GitHubProviderException("无法读取 GitHub 用户身份。");
            }
            return user;
        } catch (RestClientException exception) {
            throw new GitHubProviderException("暂时无法读取 GitHub 用户身份。", exception);
        }
    }

    private <T> T oauthPost(String path, MultiValueMap<String, String> form, Class<T> responseType) {
        try {
            return oauthClient
                    .post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientException exception) {
            throw new GitHubProviderException("暂时无法连接 GitHub 授权服务。", exception);
        }
    }

    private static boolean isGitHubVerificationUri(String value) {
        if (isBlank(value)) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && "/login/device".equals(uri.getPath())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record DeviceAuthorization(
            @JsonProperty("device_code") String deviceCode,
            @JsonProperty("user_code") String userCode,
            @JsonProperty("verification_uri") String verificationUri,
            @JsonProperty("expires_in") Long expiresIn,
            Integer interval) {}

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            String scope,
            String error,
            @JsonProperty("error_description") String errorDescription) {

        public boolean hasAccessToken() {
            return !isBlank(accessToken);
        }
    }

    public record GitHubUser(
            long id,
            String login,
            String name,
            @JsonProperty("avatar_url") String avatarUrl) implements Principal, Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public String getName() {
            return login;
        }
    }

    public static class GitHubProviderException extends RuntimeException {

        public GitHubProviderException(String message) {
            super(message);
        }

        public GitHubProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
