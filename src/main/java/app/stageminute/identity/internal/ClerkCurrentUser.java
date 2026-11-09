package app.stageminute.identity.internal;

import app.stageminute.account.AccountService;
import app.stageminute.account.NewAccountContext;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.CurrentUser;
import app.stageminute.platform.Reason;
import app.stageminute.platform.RequestContext;
import app.stageminute.platform.UserId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Turns the verified Clerk token of this request into our {@link UserId}. The first call of a new person
 * creates their account row (with the new-account limits of F-086); later calls in the same request reuse the
 * answer. The user id is also put into the logging context, so every log line of the request carries it (and
 * nothing else about the person). Nothing else in the server reads the token.
 */
@Component
class ClerkCurrentUser implements CurrentUser {

    private static final String REQUEST_ATTRIBUTE = ClerkCurrentUser.class.getName() + ".userId";
    /** The app makes a random id at first launch; anything that does not look like one is treated as missing. */
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9_-]{8,128}");

    private static final Set<String> PLATFORMS = Set.of("ios", "android");

    private final AccountService accounts;

    ClerkCurrentUser(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserId require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new BusinessRefusal(Reason.SESSION_EXPIRED);
        }
        HttpServletRequest request = currentRequest();
        if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof UserId known) {
            return known;
        }
        UserId user = accounts.resolve(token.getToken().getSubject(), contextOf(request));
        request.setAttribute(REQUEST_ATTRIBUTE, user);
        RequestContext.bindUser(user.toString());
        return user;
    }

    private static NewAccountContext contextOf(HttpServletRequest request) {
        String device = request.getHeader("X-Device-Id");
        String platform = request.getHeader("X-Platform");
        return new NewAccountContext(
                device != null && DEVICE_ID.matcher(device).matches() ? device : null,
                platform != null && PLATFORMS.contains(platform) ? platform : null,
                request.getRemoteAddr());
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest();
        }
        throw new IllegalStateException("CurrentUser.require() was called outside a web request");
    }
}
