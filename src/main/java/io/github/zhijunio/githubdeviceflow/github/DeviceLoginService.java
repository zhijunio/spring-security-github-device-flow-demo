package io.github.zhijunio.githubdeviceflow.github;

import static io.github.zhijunio.githubdeviceflow.config.OAuth2ClientConfiguration.REGISTRATION_ID;
import static io.github.zhijunio.githubdeviceflow.github.DeviceCodeOAuth2AuthorizedClientProvider.DEVICE_CODE_ATTRIBUTE;

import io.github.zhijunio.githubdeviceflow.config.GitHubDeviceFlowProperties;
import io.github.zhijunio.githubdeviceflow.github.GitHubDeviceFlowClient.DeviceAuthorization;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceLoginService {

    private final GitHubDeviceFlowProperties properties;
    private final GitHubDeviceFlowClient gitHub;
    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final DefaultOAuth2UserService userService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, DeviceTransaction> transactions = new ConcurrentHashMap<>();

    public DeviceLoginService(
            GitHubDeviceFlowProperties properties,
            GitHubDeviceFlowClient gitHub,
            OAuth2AuthorizedClientManager authorizedClientManager,
            OAuth2AuthorizedClientService authorizedClientService,
            DefaultOAuth2UserService userService,
            Clock clock) {
        this.properties = properties;
        this.gitHub = gitHub;
        this.authorizedClientManager = authorizedClientManager;
        this.authorizedClientService = authorizedClientService;
        this.userService = userService;
        this.clock = clock;
    }

    public DeviceLoginSnapshot start(String ownerSessionId) {
        if (!properties.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务端尚未配置 GitHub Client ID。");
        }

        Instant now = clock.instant();
        DeviceAuthorization authorization = gitHub.requestDeviceAuthorization();
        DeviceTransaction transaction = new DeviceTransaction(
                randomId(),
                ownerSessionId,
                authorization.deviceCode(),
                authorization.userCode(),
                authorization.verificationUri(),
                now.plusSeconds(authorization.expiresIn()),
                authorization.interval(),
                now.plusSeconds(authorization.interval()));
        transactions.put(transaction.id, transaction);
        return snapshot(transaction, now);
    }

    public DeviceLoginSnapshot poll(String id, String ownerSessionId) {
        DeviceTransaction transaction = requireOwned(id, ownerSessionId);
        synchronized (transaction) {
            Instant now = clock.instant();
            if (transaction.state != State.PENDING) return snapshot(transaction, now);
            if (!transaction.expiresAt.isAfter(now)) {
                transaction.state = State.EXPIRED;
                transaction.deviceCode = null;
                return snapshot(transaction, now);
            }
            if (transaction.nextPollAt.isAfter(now)) return snapshot(transaction, now);

            Instant attemptedAt = now;
            transaction.nextPollAt = attemptedAt.plusSeconds(transaction.intervalSeconds);
            OAuth2AuthorizedClient authorizedClient;
            try {
                OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
                        .principal("device-flow:" + transaction.id)
                        .attribute(DEVICE_CODE_ATTRIBUTE, transaction.deviceCode)
                        .build();
                authorizedClient = authorizedClientManager.authorize(request);
            } catch (OAuth2AuthorizationException exception) {
                applyProviderError(transaction, exception);
                return snapshot(transaction, clock.instant());
            } catch (GitHubDeviceFlowClient.GitHubProviderException exception) {
                transaction.nextPollAt = clock.instant().plusSeconds(transaction.intervalSeconds);
                return snapshot(transaction, clock.instant());
            }

            if (authorizedClient == null) {
                transaction.state = State.FAILED;
                transaction.deviceCode = null;
                return snapshot(transaction, clock.instant());
            }

            try {
                OAuth2User user = userService.loadUser(new OAuth2UserRequest(
                        authorizedClient.getClientRegistration(), authorizedClient.getAccessToken()));
                transaction.authorizedClient = authorizedClient;
                transaction.user = user;
                transaction.state = State.AUTHORIZED;
                transaction.deviceCode = null;
            } catch (RuntimeException exception) {
                removeTemporaryAuthorizedClient(transaction);
                transaction.state = State.FAILED;
                transaction.deviceCode = null;
            }
            return snapshot(transaction, clock.instant());
        }
    }

    public void establishSession(
            String id, String ownerSessionId, BiConsumer<OAuth2User, OAuth2AuthorizedClient> establish) {
        DeviceTransaction transaction = requireOwned(id, ownerSessionId);
        synchronized (transaction) {
            if (transaction.state == State.AUTHENTICATED) return;
            if (transaction.state != State.AUTHORIZED
                    || transaction.user == null
                    || transaction.authorizedClient == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub 授权尚未完成。");
            }
            establish.accept(transaction.user, transaction.authorizedClient);
            removeTemporaryAuthorizedClient(transaction);
            transaction.state = State.AUTHENTICATED;
            transaction.user = null;
            transaction.authorizedClient = null;
        }
    }

    public void cancel(String id, String ownerSessionId) {
        DeviceTransaction transaction = requireOwned(id, ownerSessionId);
        synchronized (transaction) {
            if (transaction.state == State.PENDING) {
                transaction.state = State.CANCELLED;
                transaction.deviceCode = null;
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    void removeExpiredTransactions() {
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(2));
        transactions.forEach((id, transaction) -> {
            synchronized (transaction) {
                if (transaction.expiresAt.isBefore(cutoff) && transactions.remove(id, transaction)) {
                    removeTemporaryAuthorizedClient(transaction);
                    transaction.deviceCode = null;
                    transaction.user = null;
                    transaction.authorizedClient = null;
                }
            }
        });
    }

    private void applyProviderError(DeviceTransaction transaction, OAuth2AuthorizationException exception) {
        String error = exception.getError().getErrorCode();
        if ("authorization_pending".equals(error)) return;
        if ("slow_down".equals(error)) {
            transaction.intervalSeconds += 5;
            transaction.nextPollAt = clock.instant().plusSeconds(transaction.intervalSeconds);
            return;
        }
        if ("expired_token".equals(error) || "token_expired".equals(error)) {
            transaction.state = State.EXPIRED;
        } else if ("access_denied".equals(error)) {
            transaction.state = State.DENIED;
        } else {
            transaction.state = State.FAILED;
        }
        transaction.deviceCode = null;
        removeTemporaryAuthorizedClient(transaction);
    }

    private void removeTemporaryAuthorizedClient(DeviceTransaction transaction) {
        authorizedClientService.removeAuthorizedClient(REGISTRATION_ID, "device-flow:" + transaction.id);
    }

    private DeviceTransaction requireOwned(String id, String ownerSessionId) {
        DeviceTransaction transaction = transactions.get(id);
        if (transaction == null || !transaction.ownerSessionId.equals(ownerSessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "登录请求不存在或已失效。");
        }
        return transaction;
    }

    private String randomId() {
        byte[] value = new byte[24];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static DeviceLoginSnapshot snapshot(DeviceTransaction transaction, Instant now) {
        long retryAfterSeconds =
                Math.max(0, Duration.between(now, transaction.nextPollAt).toSeconds());
        if (Duration.between(now, transaction.nextPollAt).toMillis() % 1000 != 0) retryAfterSeconds++;
        return new DeviceLoginSnapshot(
                transaction.id,
                transaction.state.name(),
                transaction.userCode,
                transaction.verificationUri,
                transaction.expiresAt,
                transaction.intervalSeconds,
                retryAfterSeconds);
    }

    private static final class DeviceTransaction {
        private final String id;
        private final String ownerSessionId;
        private final String userCode;
        private final String verificationUri;
        private final Instant expiresAt;
        private int intervalSeconds;
        private Instant nextPollAt;
        private String deviceCode;
        private OAuth2User user;
        private OAuth2AuthorizedClient authorizedClient;
        private State state = State.PENDING;

        private DeviceTransaction(
                String id,
                String ownerSessionId,
                String deviceCode,
                String userCode,
                String verificationUri,
                Instant expiresAt,
                int intervalSeconds,
                Instant nextPollAt) {
            this.id = id;
            this.ownerSessionId = ownerSessionId;
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.expiresAt = expiresAt;
            this.intervalSeconds = intervalSeconds;
            this.nextPollAt = nextPollAt;
        }
    }

    private enum State {
        PENDING,
        AUTHORIZED,
        AUTHENTICATED,
        DENIED,
        EXPIRED,
        CANCELLED,
        FAILED
    }

    public record DeviceLoginSnapshot(
            String id,
            String state,
            String userCode,
            String verificationUri,
            Instant expiresAt,
            int intervalSeconds,
            long retryAfterSeconds) {}
}
