package com.ecommerce.userservice.config.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    /** Runs the filter and returns the id that the rest of the chain could see in the MDC. */
    private String run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));
        return seen.get();
    }

    @Test
    @DisplayName("a good X-Request-Id from Nginx is kept and sent back")
    void keepsAGoodId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = run(request, response);

        assertThat(seen).isEqualTo("abc-123");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("no header: a new id is made")
    void makesAnIdWhenMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = run(new MockHttpServletRequest(), response);

        assertThat(seen).isNotBlank().isEqualTo(response.getHeader("X-Request-Id"));
    }

    @Test
    @DisplayName("an id with odd characters is replaced, so nobody can write fake log lines")
    void replacesAnUnsafeId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "bad id\nINFO fake line");

        String seen = run(request, new MockHttpServletResponse());

        assertThat(seen).matches("[A-Za-z0-9-]{1,64}").isNotEqualTo("bad id\nINFO fake line");
    }

    @Test
    @DisplayName("the id is removed from the MDC after the request")
    void clearsTheMdc() throws Exception {
        run(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
