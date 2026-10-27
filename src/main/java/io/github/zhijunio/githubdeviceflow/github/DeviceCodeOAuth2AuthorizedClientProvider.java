package io.github.zhijunio.githubdeviceflow.github;

import io.github.zhijunio.githubdeviceflow.config.OAuth2ClientConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

/**
 * Adds GitHub's Device Authorization Grant to Spring Security's OAuth2 Client.
 * Spring Security has no built-in device-code provider, so the protocol-specific
 * token request is adapted to the OAuth2AuthorizedClientProvider extension point.
 */
public class DeviceCodeOAuth2AuthorizedClientProvider implements OAuth2AuthorizedClientProvider {

    static final String DEVICE_CODE_ATTRIBUTE = "device_code";

    private final GitHubDeviceFlowClient gitHub;
    private final Clock clock;

    public DeviceCodeOAuth2AuthorizedClientProvider(GitHubDeviceFlowClient gitHub, Clock clock) {
        this.gitHub = gitHub;
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizedClient authorize(OAuth2AuthorizationContext context) {
        ClientRegistration registration = context.getClientRegistration();
        if (!OAuth2ClientConfiguration.DEVICE_CODE_GRANT.equals(registration.getAuthorizationGrantType())) {
            return null;
        }

        String deviceCode = context.getAttribute(DEVICE_CODE_ATTRIBUTE);
        if (deviceCode == null || deviceCode.isBlank()) {
            throw authorizationError("invalid_request", "The device_code authorization attribute is required.");
        }

        GitHubDeviceFlowClient.TokenResponse response = gitHub.pollAccessToken(deviceCode);
        if (response == null) {
            throw authorizationError("invalid_token_response", "GitHub returned an empty token response.");
        }
        if (response.error() != null && !response.error().isBlank()) {
            throw authorizationError(response.error(), response.errorDescription());
        }
        if (response.accessToken() == null || response.accessToken().isBlank()) {
            throw authorizationError("invalid_token_response", "GitHub did not return an access token.");
        }
        if (response.tokenType() != null && !"bearer".equalsIgnoreCase(response.tokenType())) {
            throw authorizationError("invalid_token_response", "GitHub returned an unsupported token type.");
        }
        if (response.expiresIn() != null && response.expiresIn() < 1) {
            throw authorizationError("invalid_token_response", "GitHub returned an invalid token lifetime.");
        }

        Instant issuedAt = clock.instant();
        Instant expiresAt = response.expiresIn() == null ? null : issuedAt.plusSeconds(response.expiresIn());
        Set<String> scopes = parseScopes(response.scope(), registration);
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, response.accessToken(), issuedAt, expiresAt, scopes);
        OAuth2RefreshToken refreshToken =
                response.refreshToken() == null || response.refreshToken().isBlank()
                        ? null
                        : new OAuth2RefreshToken(response.refreshToken(), issuedAt);
        return new OAuth2AuthorizedClient(registration, context.getPrincipal().getName(), accessToken, refreshToken);
    }

    private static Set<String> parseScopes(String responseScope, ClientRegistration registration) {
        if (responseScope == null || responseScope.isBlank()) return registration.getScopes();
        return Arrays.stream(responseScope.split(","))
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static OAuth2AuthorizationException authorizationError(String code, String description) {
        return new OAuth2AuthorizationException(new OAuth2Error(code, description, null));
    }
}
