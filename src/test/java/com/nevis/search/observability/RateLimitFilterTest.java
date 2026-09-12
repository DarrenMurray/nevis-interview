package com.nevis.search.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The bucket itself, without a container. */
class RateLimitFilterTest {

    private static MockHttpServletResponse call(RateLimitFilter filter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        return response;
    }

    @Test
    @DisplayName("allows up to the limit, then returns 429 with Retry-After")
    void limitsAfterCapacity() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(5);

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "/search").getStatus()).as("request %d", i).isEqualTo(200);
        }

        MockHttpServletResponse limited = call(filter, "/search");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        assertThat(limited.getContentAsString()).contains("retry_after_seconds");
    }

    @Test
    @DisplayName("a rejected request does not consume a token")
    void rejectionDoesNotConsume() throws Exception {
        // Deducting on rejection lets a burst drive the balance negative, so the bucket never
        // recovers while traffic continues. This pins that it does not.
        RateLimitFilter filter = new RateLimitFilter(60);
        for (int i = 0; i < 60; i++) {
            call(filter, "/search");
        }
        for (int i = 0; i < 200; i++) {
            call(filter, "/search");
        }

        // One token is worth a second at 60/min, so the wait stays at the floor rather than
        // growing with the number of rejections.
        assertThat(call(filter, "/search").getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    @DisplayName("reports remaining capacity")
    void reportsRemaining() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(10);

        assertThat(call(filter, "/search").getHeader("X-RateLimit-Limit")).isEqualTo("10");
        assertThat(call(filter, "/search").getHeader("X-RateLimit-Remaining")).isEqualTo("8");
    }

    @Test
    @DisplayName("static assets and API docs are exempt, so the UI cannot be limited out")
    void staticAssetsAreExempt() {
        RateLimitFilter filter = new RateLimitFilter(1);

        for (String path : new String[]{"/vendor/htmx.min.js", "/favicon.svg", "/v3/api-docs"}) {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", path)))
                    .as(path).isTrue();
        }
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/search"))).isFalse();
    }
}
