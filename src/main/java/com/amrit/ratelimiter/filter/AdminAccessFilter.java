package com.amrit.ratelimiter.filter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminAccessFilter extends OncePerRequestFilter {
    @Value("${rate-limiter.admin-key:}") private String adminKey;
    @Override protected boolean shouldNotFilter(HttpServletRequest r){
        String p=org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(r);
        return !(p.startsWith("/api/admin") || p.startsWith("/api/metrics") || p.startsWith("/api/health") || (p.startsWith("/actuator") && !p.equals("/actuator/health")));
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        String provided=req.getHeader("X-Admin-Key");
        if(adminKey.length()<32){res.setStatus(503);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Admin access is not configured\"}");return;}
        if(provided==null || !MessageDigest.isEqual(adminKey.getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8))){res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Valid admin key required\"}");return;}
        chain.doFilter(req,res);
    }
}
