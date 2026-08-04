package pl.najem.app;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class StatementSizeFilterTest {

    private static final String SAMPLE = """
        :20:NAJEM1
        :25:PL61109010140000071219812874
        :28C:1/1
        -
        """;

    /**
     * The size limit is enforced by a filter, not by the handler, and these tests exercise the
     * filter directly for that reason.
     *
     * <p>A handler-level check would be untestable as a size limit: calling the method with a large
     * String proves only that the method rejects large Strings, which is not the property wanted.
     * The property wanted is that the body is never materialised — and that is decided before the
     * handler exists, so it can only be observed where the decision is made.
     */
    @Test
    void anOversizedUploadIsRefusedWithoutTheBodyEverBeingRead() throws Exception {
        var request = new MockHttpServletRequest("POST", StatementSizeFilter.PATH);
        request.setContent(new byte[(int) StatementSizeFilter.MAX_STATEMENT_BYTES + 1]);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new StatementSizeFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("exceeds");
        assertThat(chain.getRequest()).as("the request must not have reached the handler").isNull();
    }

    /** An undeclared length cannot be checked, so accepting it would make the limit optional. */
    @Test
    void anUploadThatDeclaresNoLengthIsRefused() throws Exception {
        var request = new MockHttpServletRequest("POST", StatementSizeFilter.PATH);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new StatementSizeFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("Content-Length");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void anOrdinaryStatementPassesTheFilterUntouched() throws Exception {
        var request = new MockHttpServletRequest("POST", StatementSizeFilter.PATH);
        request.setContent(SAMPLE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new StatementSizeFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).as("an ordinary upload must reach the handler").isNotNull();
    }

    /** The filter must not police anyone else's endpoints — it knows nothing about their bodies. */
    @Test
    void theFilterIgnoresEveryOtherRequest() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/acc/payments/x/confirm");
        request.setContent(new byte[(int) StatementSizeFilter.MAX_STATEMENT_BYTES + 1]);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new StatementSizeFilter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

}
