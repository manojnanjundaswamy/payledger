package dev.payledger.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.removeRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.TokenRelayFilterFunctions.tokenRelay;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/**
 * Only what the browser may call is routed. The ledger is internal and has no route here.
 */
@Configuration(proxyBeanMethods = false)
class Routes {

    @Bean
    RouterFunction<ServerResponse> coreService(GatewayProperties props) {
        return route("core-service")
                .route(path("/api/core/**"), http())
                .before(uri(props.coreServiceUrl()))
                // The browser's cookies (session id, CSRF token) stay at the gateway...
                .before(removeRequestHeader("Cookie"))
                .before(removeRequestHeader("X-XSRF-TOKEN"))
                // ...and the user's access token (refreshed when it expires) goes downstream instead.
                .filter(tokenRelay())
                .onError(ResourceAccessException.class, Routes::coreServiceUnavailable)
                .build();
    }

    /**
     * core-service is down or too slow (timeouts: spring.http.clients.* in application.properties).
     * Answer at once with a problem the web app can show, instead of a hung request or a bare 500.
     */
    static ServerResponse coreServiceUnavailable(Throwable error, ServerRequest request) {
        // A read timeout means it is there but slow; a connect timeout or refusal means it isn't there (stopped,
        // restarting): unavailable. Apache HttpClient reports a read timeout as SocketTimeoutException, the JDK client
        // as HttpTimeoutException.
        Throwable cause = error.getCause();
        boolean slow = (cause instanceof java.net.SocketTimeoutException && !(cause instanceof java.net.ConnectException))
                || (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException));
        HttpStatus status = slow ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE;
        String code = slow ? "core-service-timeout" : "core-service-unavailable";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "PayLedger didn't answer in time; try again in a moment.");
        problem.setType(URI.create("https://github.com/payledger/problems/" + code));
        problem.setTitle(code);
        problem.setProperty("code", code);
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
