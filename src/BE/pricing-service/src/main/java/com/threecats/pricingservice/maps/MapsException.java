package com.threecats.pricingservice.maps;

import com.threecats.common.exception.BaseException;
import java.util.Map;

public class MapsException extends BaseException {
    public MapsException(int status,String code,String message) {
        super(status,code,message,status==429 || status>=500,Map.of());
    }
    public static MapsException unavailable() {
        return new MapsException(503,"MAP_PROVIDER_UNAVAILABLE","Dịch vụ bản đồ tạm thời không khả dụng. Vui lòng thử lại.");
    }
    public static MapsException limited() {
        return new Limited();
    }
    private static class Limited extends MapsException {
        Limited() { super(429,"MAP_RATE_LIMITED","Vui lòng chờ một giây trước khi tra cứu lại."); }
        @Override public Map<String,Object> getDetails() { return Map.of("retry_after_seconds",2); }
    }
}
