package com.ais.security;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Defense-in-depth checks for sensitive servlet handlers. */
public final class SecurityGuards {
    private SecurityGuards() {
    }

    public static AuthorizationContext getContext(HttpServletRequest request) {
        Object value = request.getAttribute(
                AuthenticationFilter.AUTHORIZATION_CONTEXT_ATTRIBUTE);
        return value instanceof AuthorizationContext
                ? (AuthorizationContext) value : null;
    }

    /**
     * Use at the start of the /api/location/schema handler even though the
     * global AuthenticationFilter already checks the route.
     */
    public static boolean requireAdmin(HttpServletRequest request,
                                       HttpServletResponse response)
            throws IOException {
        AuthorizationContext context = getContext(request);
        if (context == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Authentication required");
            return false;
        }
        if (!context.hasRole(AuthenticationFilter.ROLE_ADMIN)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "Administrator role required");
            return false;
        }
        return true;
    }

    private static void writeError(HttpServletResponse response,
                                   int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
