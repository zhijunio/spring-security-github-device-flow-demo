package io.github.zhijunio.githubdeviceflow.github;

import io.github.zhijunio.githubdeviceflow.config.GitHubDeviceFlowProperties;
import io.github.zhijunio.githubdeviceflow.github.DeviceLoginService.DeviceLoginSnapshot;
import io.github.zhijunio.githubdeviceflow.github.GitHubDeviceFlowClient.GitHubUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeviceLoginController {

    private final DeviceLoginService deviceLoginService;
    private final GitHubDeviceFlowProperties properties;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public DeviceLoginController(
            DeviceLoginService deviceLoginService,
            GitHubDeviceFlowProperties properties,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.deviceLoginService = deviceLoginService;
        this.properties = properties;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    @GetMapping("/api/csrf")
    CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    @GetMapping("/api/config")
    ConfigResponse config() {
        return new ConfigResponse(properties.configured());
    }

    @PostMapping("/api/device-login")
    DeviceLoginSnapshot start(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        return deviceLoginService.start(session.getId());
    }

    @GetMapping("/api/device-login/{id}")
    DeviceLoginSnapshot poll(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session == null) return notFound();

        DeviceLoginSnapshot snapshot = deviceLoginService.poll(id, session.getId());
        if ("AUTHORIZED".equals(snapshot.state())) {
            deviceLoginService.establishSession(
                    id, session.getId(), user -> establishSecuritySession(user, request, response));
            return new DeviceLoginSnapshot(
                    snapshot.id(),
                    "AUTHENTICATED",
                    snapshot.userCode(),
                    snapshot.verificationUri(),
                    snapshot.expiresAt(),
                    snapshot.intervalSeconds(),
                    0);
        }
        return snapshot;
    }

    @DeleteMapping("/api/device-login/{id}")
    ResponseEntity<Void> cancel(@PathVariable String id, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.notFound().build();
        deviceLoginService.cancel(id, session.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/session")
    SessionResponse session(org.springframework.security.core.Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof GitHubUser user)) {
            return new SessionResponse(false, null);
        }
        return new SessionResponse(true, new ProfileResponse(user.id(), user.login(), user.name(), user.avatarUrl()));
    }

    @GetMapping("/api/me")
    ProfileResponse me(org.springframework.security.core.Authentication authentication) {
        GitHubUser user = (GitHubUser) authentication.getPrincipal();
        return new ProfileResponse(user.id(), user.login(), user.name(), user.avatarUrl());
    }

    private void establishSecuritySession(GitHubUser user, HttpServletRequest request, HttpServletResponse response) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private static DeviceLoginSnapshot notFound() {
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "登录请求不存在或已失效。");
    }

    public record CsrfResponse(String headerName, String token) {}

    public record ConfigResponse(boolean clientConfigured) {}

    public record SessionResponse(boolean authenticated, ProfileResponse user) {}

    public record ProfileResponse(long id, String login, String name, String avatarUrl) {}
}
