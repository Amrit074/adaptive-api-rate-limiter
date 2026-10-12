package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.filter.AdminAccessFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class AdminAccessTest {
    @Test void administrationRemainsProtectedWithPathParameters() throws Exception {
        var filter=new AdminAccessFilter();
        ReflectionTestUtils.setField(filter,"adminKey","test-only-admin-secret-of-at-least-32-characters");
        for(String path:new String[]{"/api/admin/clients","/api;version=1/admin/clients","/api/metrics/summary"}) {
            var request=new MockHttpServletRequest("GET",path);var response=new MockHttpServletResponse();var chain=new MockFilterChain();
            filter.doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(401);assertThat(chain.getRequest()).isNull();
        }
    }
}
