package com.example.ratelimiter.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.*;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PayloadLimitFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest r, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!r.getRequestURI().startsWith("/api/")) {
            chain.doFilter(r, response);
            return;
        }
        if (r.getContentLengthLong() > 8192) {
            response.sendError(413);
            return;
        }
        byte[] bytes = r.getInputStream().readNBytes(8193);
        if (bytes.length > 8192) {
            response.sendError(413);
            return;
        }
        var wrapped =
                new HttpServletRequestWrapper(r) {
                    @Override
                    public ServletInputStream getInputStream() {
                        var input = new ByteArrayInputStream(bytes);
                        return new ServletInputStream() {
                            public int read() {
                                return input.read();
                            }

                            public boolean isFinished() {
                                return input.available() == 0;
                            }

                            public boolean isReady() {
                                return true;
                            }

                            public void setReadListener(ReadListener listener) {
                                throw new UnsupportedOperationException("Blocking MVC only");
                            }
                        };
                    }
                };
        chain.doFilter(wrapped, response);
    }
}
