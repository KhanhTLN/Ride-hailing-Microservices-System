package com.threecats.common.context;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
            String correlationId = httpRequest.getHeader(CorrelationContext.CORRELATION_HEADER);
            CorrelationContext.set(correlationId);
            httpResponse.setHeader(CorrelationContext.CORRELATION_HEADER, CorrelationContext.get());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }
}
