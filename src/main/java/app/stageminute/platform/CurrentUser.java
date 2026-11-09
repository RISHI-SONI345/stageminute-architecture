package app.stageminute.platform;

/**
 * Who is calling? Controllers of every module ask here; they never read the token themselves. The
 * implementation lives in {@code identity} (it checks the Clerk token and finds or creates our account), so
 * no module needs to depend on {@code identity} just to know the caller.
 */
public interface CurrentUser {

    /**
     * Our user for this request. Refuses with {@code session_expired} when there is no verified token, and
     * ties the user id to the request's log lines.
     */
    UserId require();
}
