package com.threecats.pricingservice.maps;
import com.threecats.common.context.CorrelationContext;
import com.threecats.common.exception.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice @Order(Ordered.HIGHEST_PRECEDENCE)
public class MapsExceptionHandler {
    @ExceptionHandler(MapsException.class)
    public ResponseEntity<ErrorResponse> handle(MapsException ex) {
        var builder=ResponseEntity.status(ex.getStatus()).header("X-Correlation-ID",CorrelationContext.get());
        if(ex.getStatus()==429) builder.header("Retry-After","2");
        return builder.body(ErrorResponse.of(ex.getErrorCode(),ex.getMessage(),CorrelationContext.get(),ex.isRetryable(),ex.getDetails()));
    }
}
