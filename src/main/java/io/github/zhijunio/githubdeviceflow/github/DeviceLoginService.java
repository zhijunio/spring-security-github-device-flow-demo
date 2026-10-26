package io.github.zhijunio.githubdeviceflow.github;

import static io.github.zhijunio.githubdeviceflow.github.GitHubDeviceFlowClient.GitHubUser;
import static io.github.zhijunio.githubdeviceflow.github.GitHubDeviceFlowClient.TokenResponse;

import io.github.zhijunio.githubdeviceflow.config.GitHubDeviceFlowProperties;
import io.github.zhijunio.githubdeviceflow.github.GitHubDeviceFlowClient.DeviceAuthorization;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceLoginService {

    private final GitHubDeviceFlowProperties properties;
    private final GitHubDeviceFlowClient gitHub;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, DeviceTransaction> transactions = new ConcurrentHashMap<>();

    public DeviceLoginService(GitHubDeviceFlowProperties properties, GitHubDeviceFlowClient gitHub, Clock clock) {
        this.properties = properties;
        this.gitHub = gitHub;
        this.clock = clock;
    }

    public DeviceLoginSnapshot start(String ownerSessionId) {
        if (!properties.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务端尚未配置 GitHub Client ID。");
        }

        Instant now = clock.instant();
        transactions
                .entrySet()
                .removeIf(entry ->
                        entry.getValue().expiresAt.plus(Duration.ofMinutes(2)).isBefore(now));

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
            try {
                TokenResponse token = gitHub.pollAccessToken(transaction.deviceCode);
                if (token == null) {
                    transaction.state = State.FAILED;
                } else if ("authorization_pending".equals(token.error())) {
                    // Keep waiting at the interval supplied by GitHub.
                } else if ("slow_down".equals(token.error())) {
                    transaction.intervalSeconds += 5;
                    transaction.nextPollAt = clock.instant().plusSeconds(transaction.intervalSeconds);
                } else if ("expired_token".equals(token.error()) || "token_expired".equals(token.error())) {
                    transaction.state = State.EXPIRED;
                    transaction.deviceCode = null;
                } else if ("access_denied".equals(token.error())) {
                    transaction.state = State.DENIED;
                    transaction.deviceCode = null;
                } else if (token.hasAccessToken()) {
                    GitHubUser user = gitHub.currentUser(token.accessToken());
                    transaction.user = user;
                    transaction.state = State.AUTHORIZED;
                    transaction.deviceCode = null;
                } else {
                    transaction.state = State.FAILED;
                    transaction.deviceCode = null;
                }
            } catch (GitHubDeviceFlowClient.GitHubProviderException exception) {
                transaction.state = State.FAILED;
                transaction.deviceCode = null;
            }
            return snapshot(transaction, clock.instant());
        }
    }

    public void establishSession(String id, String ownerSessionId, Consumer<GitHubUser> establish) {
        DeviceTransaction transaction = requireOwned(id, ownerSessionId);
        synchronized (transaction) {
            if (transaction.state == State.AUTHENTICATED) return;
            if (transaction.state != State.AUTHORIZED || transaction.user == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub 授权尚未完成。");
            }
            establish.accept(transaction.user);
            transaction.state = State.AUTHENTICATED;
            transaction.user = null;
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
        private GitHubUser user;
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
