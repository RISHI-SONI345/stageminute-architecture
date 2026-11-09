package app.stageminute.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id before anything else runs: it goes into the logging context, onto the
 * response as {@code X-Request-Id} (set first, so error responses carry it too) and is cleared at the
 * end. The filter never reads the body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

    private static final String ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The error page and async work run as new dispatches of the same request: keep the first id.
        String id = (String) request.getAttribute(ID_ATTRIBUTE);
        if (id == null) {
            id = RequestContext.acceptOrCreate(request.getHeader(RequestContext.HEADER));
            request.setAttribute(ID_ATTRIBUTE, id);
        }
        RequestContext.bindRequestId(id);
        response.setHeader(RequestContext.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }
}
