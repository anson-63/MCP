package com.ais.security;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable, server-derived identity and authorization data for one request.
 *
 * Never populate this object from query parameters, JSON request fields, or
 * untrusted X-User/X-Role headers. AuthenticationFilter builds it from the
 * principal and roles supplied by the servlet container/enterprise SSO.
 */
public final class AuthorizationContext implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String username;
    private final Set<String> roles;
    private final Set<String> allowedDepartments;
    
    public static final String REQUEST_ATTRIBUTE =
            AuthorizationContext.class.getName();
    
    public AuthorizationContext(String username, Set<String> roles,
                                Set<String> allowedDepartments) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        this.username = username;
        this.roles = immutableCopy(roles);
        this.allowedDepartments = immutableUppercaseCopy(allowedDepartments);
    }

    public String getUsername() {
        return username;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
    }

    /**
     * Empty means no department scope has been assigned yet. Data-access code
     * must choose an explicit policy for that case; it must not silently treat
     * an empty set as unrestricted access.
     */
    public Set<String> getAllowedDepartments() {
        return allowedDepartments;
    }

    public boolean canAccessDepartment(String departmentCode) {
        return departmentCode != null
                && allowedDepartments.contains(departmentCode.trim().toUpperCase());
    }

    private static Set<String> immutableCopy(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<String>(values));
    }

    private static Set<String> immutableUppercaseCopy(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new LinkedHashSet<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                normalized.add(value.trim().toUpperCase());
            }
        }
        return Collections.unmodifiableSet(normalized);
    }
}
