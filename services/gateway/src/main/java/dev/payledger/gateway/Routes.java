package dev.payledger.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

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
                .build();
    }
}
