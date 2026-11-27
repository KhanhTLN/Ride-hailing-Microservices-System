package com.threecats.pricingservice.maps;

import com.threecats.pricingservice.dto.QuoteInputRequest;
import com.threecats.pricingservice.model.QuoteRecord;
import com.threecats.pricingservice.service.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import org.springframework.transaction.TransactionDefinition;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapsQuoteTest {
    QuoteStore store;
    RoutingService routing;
    PricingCalculator calculator;
    QuoteInputRequest input;
    @BeforeEach void setup() {
        store=mock(QuoteStore.class); routing=mock(RoutingService.class);
        var manager=new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx,TransactionDefinition definition) {}
            protected void doCommit(DefaultTransactionStatus status) {}
            protected void doRollback(DefaultTransactionStatus status) {}
        };
        calculator=new PricingCalculator(store,routing,manager);
        input=new QuoteInputRequest(); input.setPickup(MapsProviderTest.point(21.02,105.85)); input.setDropoff(MapsProviderTest.point(21.03,105.86)); input.setVehicle_type("MOTORBIKE");
        when(routing.route(any(),any(),any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"No transaction during network I/O");
            return new RoutingService.Route(4322,913,Map.of(),"OSRM","driving","driving");
        });
    }
    @Test void quoteUsesAuthoritativeRoadMetricsAndExistingTariff() {
        var result=calculator.calculateQuote(input,"passenger","key");
        assertEquals(4322,result.getEstimatedDistanceM()); assertEquals(913,result.getEstimatedDurationS());
        assertEquals("32000",result.getFare().getAmount());
        assertEquals(120,result.getExpiresAt().getEpochSecond()-result.getCreatedAt().getEpochSecond());
        verify(store).lockKey("passenger:key"); verify(store).save("passenger:key",result);
    }
    @Test void carFareUsesRoadDistance() {
        input.setVehicle_type("CAR");
        assertEquals("64000",calculator.calculateQuote(input,"passenger","key").getFare().getAmount());
    }
    @Test void replayKeepsStoredQuoteEvenWhenProviderIsUnavailable() {
        QuoteRecord quote=calculator.calculateQuote(input,"passenger","key");
        when(store.findByIdempotencyKey("passenger:key")).thenReturn(Optional.of(quote));
        clearInvocations(routing);
        assertSame(quote,calculator.calculateQuote(input,"passenger","key")); verifyNoInteractions(routing);
        input.setVehicle_type("CAR");
        assertThrows(com.threecats.common.exception.BaseException.class,() -> calculator.calculateQuote(input,"passenger","key"));
        verifyNoInteractions(routing);
    }
    @Test void concurrentReplayIsRecheckedInsideTransaction() {
        QuoteRecord quote=calculator.calculateQuote(input,"passenger","other");
        when(store.findByIdempotencyKey("passenger:key")).thenReturn(Optional.empty(),Optional.of(quote));
        clearInvocations(store);
        assertSame(quote,calculator.calculateQuote(input,"passenger","key"));
        verify(store,never()).save(eq("passenger:key"),any());
    }
    @Test void providerFailureDoesNotPersistQuote() {
        when(routing.route(any(),any(),any())).thenThrow(MapsException.unavailable());
        assertThrows(MapsException.class,() -> calculator.calculateQuote(input,"passenger","key"));
        verify(store,never()).save(any(),any()); verify(store,never()).lockKey(any());
    }
}
