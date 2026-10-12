package dev.payledger.core.config;

import dev.payledger.core.user.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUser;

    WebConfig(CurrentUserArgumentResolver currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUser);
    }
}
