package com.richwavelet.backend.support;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.security.Principal;

/**
 * Puts a fake {@link Authentication} in scope for standalone MockMvc tests.
 *
 * <p>Controllers take an {@code Authentication} parameter, which Spring MVC resolves from
 * {@code HttpServletRequest#getUserPrincipal()}. In a real deployment Spring Security's
 * {@code SecurityContextHolderAwareRequestWrapper} supplies it; with
 * {@code MockMvcBuilders.standaloneSetup(...)} there is no such wrapper, so setting the
 * security context alone leaves the argument null. This filter sets the context <em>and</em>
 * wraps the request so the principal resolves.
 */
public final class TestAuthenticationFilter implements Filter {

    private final Authentication authentication;

    public TestAuthenticationFilter(Authentication authentication) {
        this.authentication = authentication;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest) request) {
                @Override
                public Principal getUserPrincipal() {
                    return authentication;
                }
            }, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
