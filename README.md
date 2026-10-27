# Spring Security GitHub Device Flow Demo

[![Java](https://img.shields.io/badge/Java-25-blue)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-6DB33F)](https://spring.io/projects/spring-boot)
[![Spring Security](https://img.shields.io/badge/Spring%20Security-7.1.2-6DB33F)](https://spring.io/projects/spring-security)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![GitHub Device Flow](https://img.shields.io/badge/GitHub-Device%20Flow-181717?logo=github)](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow)

A runnable tutorial sample that signs a browser into a Spring Security session through GitHub OAuth Device Flow.

> This sample uses Spring Security OAuth2 Client's `ClientRegistration`, `OAuth2AuthorizedClientManager`, `OAuth2AuthorizedClientService`, and `OAuth2UserService`. Spring Security does not provide a built-in Device Authorization Grant provider, so the sample adds a small `OAuth2AuthorizedClientProvider` for GitHub's device-code token exchange. It does not use `oauth2Login()`, which follows the Authorization Code Grant.

## Requirements

- Java 25
- GitHub OAuth App with Device Flow enabled
- GitHub OAuth App **Client ID**

Device Flow does not require the OAuth App Client Secret. Do not put a secret in this project or send one to anyone helping with setup.

## Create and configure a GitHub OAuth App

1. Open GitHub **Settings → Developer settings → OAuth Apps → New OAuth App**.
2. Fill in the application name and homepage URL. For local use, `http://localhost:8080` is sufficient.
3. If GitHub asks for an Authorization callback URL, enter `http://localhost:8080/`. Device Flow does not use the callback URL.
4. Create the app, open its settings, enable **Device Flow**, and save.
5. Copy the app's **Client ID**. Do not copy or configure the Client Secret.

GitHub's settings can change; see the [official OAuth App creation guide](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app) and [Device Flow guide](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow).

## Run locally

In zsh or bash, export the Client ID in the same terminal used to start the app:

```sh
export GITHUB_CLIENT_ID='your-oauth-app-client-id'
./mvnw spring-boot:run
```

Alternatively, copy `.env.sample` to `.env`, set `GITHUB_CLIENT_ID`, and start the app. Spring Boot imports this local properties file. Restart the app after changing the value.

Open <http://localhost:8080>. The Maven build installs the pinned Node/npm versions, checks the frontend, builds it, and serves it from the Spring Boot application.

To confirm the running process loaded the setting, open <http://localhost:8080/api/config>; it should return `{"clientConfigured":true}`. Environment changes do not update an already-running process, so restart it after setting the variable.

The app requests only the `read:user` scope. Its UI displays the short-lived user code and verification link. After approval, the custom provider polls GitHub through the OAuth2 Client manager; `DefaultOAuth2UserService` reads `/user`, and Spring Security establishes the browser session. The GitHub access token and device code stay on the server.

## Try the flow

1. Select **Start GitHub authorization**.
2. Open the GitHub verification page and sign in if needed.
3. Enter the user code shown by the sample and approve the requested scope.
4. Return to the sample; it polls at GitHub's requested interval and updates the page after login.
5. Select **Log out** to clear the local Spring Security session.

For a direct session check, `GET /api/me` returns `401` before login and the GitHub profile stored in the local session after login. It does not create a persistent local account. A production application should map the stable GitHub numeric user ID to its own account and authorities.

## Flow and boundaries

```mermaid
sequenceDiagram
    actor User
    participant Browser
    participant App as Spring Boot app
    participant GitHub
    Browser->>App: POST /api/device-login (CSRF)
    App->>GitHub: POST /login/device/code
    GitHub-->>App: device_code, user_code, interval, expiry
    App-->>Browser: user_code and verification_uri
    User->>GitHub: Enter code and approve read:user
    loop GitHub-provided interval
        App->>GitHub: POST /login/oauth/access_token
        GitHub-->>App: authorization_pending or access_token
    end
    App->>GitHub: GET /user with bearer token
    GitHub-->>App: GitHub profile
    App->>App: Create Authentication and save SecurityContext in HTTP session
    App-->>Browser: Rotated session cookie; authenticated session
```

`GitHubDeviceFlowClient` starts the device authorization request. The custom provider performs token polling through `OAuth2AuthorizedClientManager`, and `DefaultOAuth2UserService` retrieves the GitHub profile. Spring Security creates an `OAuth2AuthenticationToken`, rotates the session ID, stores the authorized client in the HTTP session, and persists the `SecurityContext`. Device transactions and the manager's temporary authorized clients are kept in process memory; a scheduled cleanup removes expired transactions. This is a single-instance learning example rather than a horizontally scaled production design.

## Security notes

- Only the `user_code` and GitHub verification URI are sent to the browser; `device_code` and access tokens remain server-side.
- Polling follows GitHub's returned `interval`; `slow_down` adds five seconds. Denied and expired transactions become terminal.
- Temporary browser polling failures and GitHub device-token endpoint connection failures are retried; browser retries use a delay that grows up to 30 seconds. Denial, expiry, and other terminal GitHub authorization errors end the transaction.
- Device transactions are bound to the initiating HTTP session and use an opaque random ID.
- The GitHub verification URI is checked before it is returned to the browser.
- HTTP sessions use `HttpOnly` and `SameSite=Lax` cookies. Set `SESSION_COOKIE_SECURE=true` behind HTTPS.
- The authenticated profile and authorized client are stored in the HTTP session; the sample does not persist local users or roles.

## Create the project with Spring Initializr

Select Spring Boot 4.1.2, Java 25, Maven, and add Spring Security, OAuth2 Client, Spring RestClient, Spring Jackson, and Spring Web MVC. The `spring-boot-developer` skill can generate the same baseline; see its [repository](https://github.com/zhijunio/skills/tree/main/spring-boot-developer).
