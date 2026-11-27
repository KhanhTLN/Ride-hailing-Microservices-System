package com.threecats.common.context;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CorrelationFilterTest {
    @AfterEach void clear() { CorrelationContext.clear(); }
    @Test void invalidHeaderCannotBecomeAnInvalidKafkaCorrelation() throws Exception {
        var request=new MockHttpServletRequest(); request.addHeader("X-Correlation-ID","not-a-uuid");
        var response=new MockHttpServletResponse(); var called=new AtomicBoolean();
        new CorrelationFilter().doFilter(request,response,(req,res) -> called.set(true));
        assertEquals(422,response.getStatus()); assertFalse(called.get());
        String id=response.getHeader("X-Correlation-ID"); assertNotNull(id);
        assertDoesNotThrow(() -> UUID.fromString(id));
        assertTrue(response.getContentAsString().contains(id));
    }
    @Test void absentHeaderGetsAnId() throws Exception {
        var response=new MockHttpServletResponse(); var called=new AtomicBoolean();
        new CorrelationFilter().doFilter(new MockHttpServletRequest(),response,(req,res) -> called.set(true));
        assertTrue(called.get()); assertDoesNotThrow(() -> UUID.fromString(response.getHeader("X-Correlation-ID")));
    }
    @Test void validHeaderPropagatesAndIsClearedAfterRequest() throws Exception {
        String id=UUID.randomUUID().toString(); var request=new MockHttpServletRequest(); request.addHeader("X-Correlation-ID",id);
        var response=new MockHttpServletResponse();
        new CorrelationFilter().doFilter(request,response,(req,res) -> assertEquals(id,CorrelationContext.get()));
        assertEquals(id,response.getHeader("X-Correlation-ID")); assertNotEquals(id,CorrelationContext.get());
    }
}
