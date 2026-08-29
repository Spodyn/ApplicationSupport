package com.unifiedsupportinbox.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

final class SessionUserRefreshFilter extends OncePerRequestFilter {

    private final UserAccountRepository users;
    private final SecurityContextRepository securityContexts;

    SessionUserRefreshFilter(
            UserAccountRepository users,
            SecurityContextRepository securityContexts) {
        this.users = users;
        this.securityContexts = securityContexts;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing == null
                || !existing.isAuthenticated()
                || !(existing.getPrincipal() instanceof UsiSessionPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        UserAccount user = users.findById(principal.userId()).orElse(null);
        if (user == null || !user.isSessionEligibleAt(Instant.now())) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            filterChain.doFilter(request, response);
            return;
        }

        UsiSessionPrincipal refreshedPrincipal = UsiSessionPrincipal.from(user);
        UsernamePasswordAuthenticationToken refreshedAuthentication =
                new UsernamePasswordAuthenticationToken(
                        refreshedPrincipal,
                        null,
                        refreshedPrincipal.authorities());
        refreshedAuthentication.setDetails(existing.getDetails());

        SecurityContext context = SecurityContextHolder.getContext();
        context.setAuthentication(refreshedAuthentication);
        securityContexts.saveContext(context, request, response);
        filterChain.doFilter(request, response);
    }
}
