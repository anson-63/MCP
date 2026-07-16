package com.ais.security;

import java.io.IOException;
import java.security.Principal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.ais.config.AppConfig;
/**
 * Fail-closed authentication and endpoint RBAC for AIS APIs and reports.
 *
 * This filter is identity-provider neutral. It uses the principal and roles
 * established by Tomcat Realm/enterprise SSO. For local development, the
 * supplied web.xml fragment uses Tomcat BASIC authentication.
 */
public final class AuthenticationFilter implements Filter {
    public static final String AUTHORIZATION_CONTEXT_ATTRIBUTE =
            "com.ais.security.authorizationContext";

    public static final String ROLE_USER = "AIS_USER";
    public static final String ROLE_ADMIN = "AIS_ADMIN";

    private static final String SCHEMA_PATH = "/api/location/schema";

    @Override
    public void init(FilterConfig filterConfig) {
        // No mutable global state.
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
    	HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Check the property from your AppConfig
        if (!AppConfig.isSecurityEnabled()) {
            // AUTO-LOGIN BYPASS
            Set<String> roles = new LinkedHashSet<>();
            roles.add(ROLE_USER);
            roles.add(ROLE_ADMIN);
            
            AuthorizationContext context = new AuthorizationContext(
                    "dev-user", roles, Collections.<String>emptySet());
            
            httpRequest.setAttribute(AuthorizationContext.REQUEST_ATTRIBUTE, context);
            chain.doFilter(httpRequest, httpResponse);
            return; 
        }

        // --- REGULAR SECURITY LOGIC BELOW ---
        addSecurityHeaders(httpResponse);
        Principal principal = authenticate(httpRequest, httpResponse);
        
        if (principal == null) {
            if (!httpResponse.isCommitted()) {
                writeJsonError(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            }
            return;
        }

        boolean admin = httpRequest.isUserInRole(ROLE_ADMIN);
        boolean user = admin || httpRequest.isUserInRole(ROLE_USER);
        String path = applicationPath(httpRequest);

        if (isSchemaPath(path)) {
            if (!admin) {
                writeJsonError(httpResponse,
                        HttpServletResponse.SC_FORBIDDEN,
                        "Administrator role required");
                return;
            }
        } else if (!user) {
            writeJsonError(httpResponse,
                    HttpServletResponse.SC_FORBIDDEN,
                    "AIS user role required");
            return;
        }

        Set<String> roles = new LinkedHashSet<String>();
        if (user) {
            roles.add(ROLE_USER);
        }
        if (admin) {
            roles.add(ROLE_ADMIN);
        }

        // Department permissions intentionally start empty. Replace the empty
        // set with values loaded from a trusted server-side directory/policy
        // store before enabling department-scoped data access.
        AuthorizationContext context = new AuthorizationContext(
                principal.getName(), roles, Collections.<String>emptySet());
        httpRequest.setAttribute(AUTHORIZATION_CONTEXT_ATTRIBUTE, context);

        chain.doFilter(httpRequest, httpResponse);
    }

    private static Principal authenticate(HttpServletRequest request,
                                          HttpServletResponse response)
            throws IOException, ServletException {
        Principal principal = request.getUserPrincipal();
        if (principal != null) {
            return principal;
        }

        // Uses the auth mechanism configured in web.xml/Tomcat (BASIC locally,
        // or a container-integrated enterprise SSO mechanism in production).
        if (!request.authenticate(response)) {
            return null;
        }
        return request.getUserPrincipal();
    }

    static String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = uri;
        if (contextPath != null && !contextPath.isEmpty()
                && uri.startsWith(contextPath)) {
            path = uri.substring(contextPath.length());
        }

        // Ignore path parameters such as ;jsessionid=... when deciding roles.
        int matrixParam = path.indexOf(';');
        if (matrixParam >= 0) {
            path = path.substring(0, matrixParam);
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static boolean isSchemaPath(String path) {
        return SCHEMA_PATH.equals(path) || path.startsWith(SCHEMA_PATH + "/");
    }

    private static void addSecurityHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");

        // Baseline policy that will not block the existing inline JSP styles
        // and bootstrap script. Replace them with nonced resources before
        // adding restrictive default-src/script-src/style-src directives.
        response.setHeader("Content-Security-Policy",
                "object-src 'none'; base-uri 'self'; frame-ancestors 'self'");
    }

    private static void writeJsonError(HttpServletResponse response,
                                       int status, String message)
            throws IOException {
        response.resetBuffer();
        addSecurityHeaders(response);
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
        response.flushBuffer();
    }

    @Override
    public void destroy() {
        // Nothing to release.
    }
}
