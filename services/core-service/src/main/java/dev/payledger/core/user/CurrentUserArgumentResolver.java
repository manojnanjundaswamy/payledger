package dev.payledger.core.user;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Lets controllers declare a {@link CurrentUser} parameter instead of handling tokens themselves. */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final UserProvisioning provisioning;

    CurrentUserArgumentResolver(UserProvisioning provisioning) {
        this.provisioning = provisioning;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(CurrentUser.class);
    }

    @Override
    public CurrentUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                       NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("CurrentUser requires an authenticated API request");
        }
        return provisioning.resolve(token.getToken());
    }
}
