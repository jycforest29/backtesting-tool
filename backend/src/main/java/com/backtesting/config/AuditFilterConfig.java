package com.backtesting.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

@Configuration
public class AuditFilterConfig {

    /**
     * Wraps requests so we can read the body in the interceptor.
     */
    @Bean
    public FilterRegistrationBean<Filter> contentCachingFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                if (request instanceof HttpServletRequest httpReq) {
                    ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(httpReq);
                    chain.doFilter(wrappedRequest, response);
                } else {
                    chain.doFilter(request, response);
                }
            }
        });
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
}