package app.stageminute.identity.internal;

import app.stageminute.platform.Reason;
import app.stageminute.platform.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * The one answer to a request with no usable token (06-API: {@code 401 session_expired}). Whatever the cause
 * (no header, bad signature, wrong issuer, expired) the body is the same problem, so it tells a probing caller
 * nothing; only an expired token adds {@code "cause":"expired"}, which the app needs to show the sign-in-again
 * sheet (F-011). The library's own message (it can name a key or a claim) is never copied into the response.
 */
@Component
class SessionProblemEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        StringBuilder body = new StringBuilder("{")
                .append("\"type\":\"https://stageminute.app/problems/session_expired\",")
                .append("\"title\":\"")
                .append(Reason.SESSION_EXPIRED.title())
                .append("\",\"status\":401,\"reason\":\"session_expired\"");
        if (isExpired(authException)) {
            body.append(",\"cause\":\"expired\"");
        }
        String requestId = RequestContext.requestId();
        if (requestId != null) {
            // RequestContext only keeps ids made of letters, digits, dot, dash and underscore.
            body.append(",\"request_id\":\"").append(requestId).append('"');
        }
        body.append('}');

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body.toString());
    }

    private static boolean isExpired(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof JwtValidationException validation
                    && validation.getErrors().stream()
                            .anyMatch(e -> ClerkTokenValidators.EXPIRED.equals(e.getErrorCode()))) {
                return true;
            }
        }
        return false;
    }
}
