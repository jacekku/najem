package pl.najem.acc.adapter.mt940;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses an oversized statement upload <strong>before</strong> the body is read.
 *
 * <p>A check inside the handler is too late: {@code @RequestBody String} is resolved by Spring's
 * message converter before the method is invoked, so by the time handler code could look at the
 * length, the whole body is already a String on the heap — at roughly twice its transferred size.
 * The only place a size limit does what it claims is upstream of argument resolution.
 *
 * <p>A request with no {@code Content-Length} — chunked transfer — is refused for the same reason:
 * an unknown length cannot be checked, and accepting it would make the limit bypassable by setting
 * a header. Real bank exports are ordinary sized bodies.
 */
@Component
public class StatementSizeFilter extends OncePerRequestFilter {

    /** A month of statements for a large portfolio is tens of kilobytes. A megabyte is generous. */
    static final long MAX_STATEMENT_BYTES = 1_000_000L;

    static final String PATH = "/api/acc/statements";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared < 0) {
            refuse(response, "A statement upload must declare its Content-Length");
            return;
        }
        if (declared > MAX_STATEMENT_BYTES) {
            refuse(response, "Statement of " + declared + " bytes exceeds the "
                + MAX_STATEMENT_BYTES + " byte limit");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 413 rather than 400: the body may be a perfectly well-formed MT940 file that is simply too
     * large, and telling its sender their format is wrong sends them to debug the wrong thing.
     */
    private static void refuse(HttpServletResponse response, String why) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(why);
    }
}
