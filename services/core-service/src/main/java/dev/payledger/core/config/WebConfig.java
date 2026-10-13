package dev.payledger.core.config;

import dev.payledger.core.admin.StaffGate;
import dev.payledger.core.user.CurrentUserArgumentResolver;
import dev.payledger.core.user.UserEvents;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUser;
    private final StaffGate staffGate;
    private final UserEvents userEvents;

    WebConfig(CurrentUserArgumentResolver currentUser, StaffGate staffGate, UserEvents userEvents) {
        this.currentUser = currentUser;
        this.staffGate = staffGate;
        this.userEvents = userEvents;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(staffGate).addPathPatterns("/api/core/admin/**");
        registry.addInterceptor(userEvents).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUser);
    }
}
