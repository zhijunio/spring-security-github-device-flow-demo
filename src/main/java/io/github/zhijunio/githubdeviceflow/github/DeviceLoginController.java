package io.github.zhijunio.githubdeviceflow.github;

import io.github.zhijunio.githubdeviceflow.config.GitHubDeviceFlowProperties;
import io.github.zhijunio.githubdeviceflow.github.DeviceLoginService.DeviceLoginSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
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
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public DeviceLoginController(
            DeviceLoginService deviceLoginService,
            GitHubDeviceFlowProperties properties,
            OAuth2AuthorizedClientService authorizedClientService,
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.deviceLoginService = deviceLoginService;
        this.properties = properties;
        this.authorizedClientService = authorizedClientService;
        this.authorizedClientRepository = authorizedClientRepository;
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
                    id, session.getId(), (user, client) -> establishSecuritySession(user, client, request, response));
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
        if (authentication == null || !(authentication.getPrincipal() instanceof OAuth2User user)) {
            return new SessionResponse(false, null);
        }
        return new SessionResponse(true, profile(user));
    }

    @GetMapping("/api/me")
    ProfileResponse me(org.springframework.security.core.Authentication authentication) {
        return profile((OAuth2User) authentication.getPrincipal());
    }

    private void establishSecuritySession(
            OAuth2User user, OAuth2AuthorizedClient client, HttpServletRequest request, HttpServletResponse response) {
        List<org.springframework.security.core.GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        var authentication = new OAuth2AuthenticationToken(user, authorities, "github");
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        OAuth2AuthorizedClient userClient = new OAuth2AuthorizedClient(
                client.getClientRegistration(),
                authentication.getName(),
                client.getAccessToken(),
                client.getRefreshToken());
        authorizedClientRepository.saveAuthorizedClient(userClient, authentication, request, response);
        authorizedClientService.removeAuthorizedClient("github", client.getPrincipalName());
        securityContextRepository.saveContext(context, request, response);
    }

    private static ProfileResponse profile(OAuth2User user) {
        Object id = user.getAttribute("id");
        Object login = user.getAttribute("login");
        Object name = user.getAttribute("name");
        Object avatarUrl = user.getAttribute("avatar_url");
        long numericId = id instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(id));
        return new ProfileResponse(
                numericId,
                String.valueOf(login),
                name == null ? null : String.valueOf(name),
                avatarUrl == null ? null : String.valueOf(avatarUrl));
    }

    private static DeviceLoginSnapshot notFound() {
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "登录请求不存在或已失效。");
    }

    public record CsrfResponse(String headerName, String token) {}

    public record ConfigResponse(boolean clientConfigured) {}

    public record SessionResponse(boolean authenticated, ProfileResponse user) {}

    public record ProfileResponse(long id, String login, String name, String avatarUrl) {}
}
