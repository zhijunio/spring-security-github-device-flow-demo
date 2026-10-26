package io.github.zhijunio.githubdeviceflow.config;

import io.github.zhijunio.githubdeviceflow.github.GitHubDeviceFlowClient.GitHubProviderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(GitHubProviderException.class)
    ResponseEntity<ProblemDetail> providerUnavailable(GitHubProviderException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
        detail.setTitle("GitHub 授权服务暂不可用");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(detail);
    }
}
