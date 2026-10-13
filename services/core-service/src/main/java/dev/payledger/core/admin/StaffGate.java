package dev.payledger.core.admin;

import dev.payledger.core.user.UserProvisioning;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Turns away everyone who isn't staff from /api/core/admin/** before anything else runs: request bodies aren't
 * read or validated, so a customer learns nothing about the admin API (always 403 not-staff). Each endpoint then
 * checks its own permission with {@link StaffService#require}.
 */
@Component
public class StaffGate implements HandlerInterceptor {

    private final UserProvisioning users;
    private final StaffService staff;

    StaffGate(UserProvisioning users, StaffService staff) {
        this.users = users;
        this.staff = staff;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)
                || staff.roleOf(users.resolve(token.getToken())).isEmpty()) {
            throw new StaffService.NotStaffException();
        }
        return true;
    }
}
