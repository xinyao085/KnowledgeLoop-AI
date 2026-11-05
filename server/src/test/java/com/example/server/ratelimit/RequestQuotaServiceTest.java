package com.example.server.ratelimit;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.springframework.mock.env.MockEnvironment;
import com.example.server.exception.BusinessException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class RequestQuotaServiceTest {
    @Test void checksUserAndGlobalBusinessLimits() {
        var redis=mock(RedissonClient.class);var limiter=mock(RRateLimiter.class);
        when(redis.getRateLimiter(anyString())).thenReturn(limiter);when(limiter.tryAcquire()).thenReturn(true);
        new RequestQuotaService(redis,new MockEnvironment()).entry("teaching",7L);
        verify(redis).getRateLimiter("quota:v1:entry:teaching:user:7:10");
        verify(redis).getRateLimiter("quota:v1:entry:teaching:global:100");
    }
    @Test void holdsConcurrencyUntilCallEndsAndReleasesExactlyOnce() {
        var redis=mock(RedissonClient.class);var limiter=mock(RRateLimiter.class);
        when(redis.getRateLimiter(anyString())).thenReturn(limiter);when(limiter.tryAcquire()).thenReturn(true);
        var service=new RequestQuotaService(redis,new MockEnvironment().withProperty("quota.model.llm.concurrency","1"));
        var permit=service.model("llm");
        assertThatThrownBy(()->service.model("llm")).isInstanceOf(BusinessException.class);
        permit.close();permit.close();
        try(var next=service.model("llm")) {
            assertThatThrownBy(()->service.model("llm")).isInstanceOf(BusinessException.class);
        }
    }
    @Test void redisFailureFailsClosedAndDoesNotLeakConcurrency() {
        var redis=mock(RedissonClient.class);
        when(redis.getRateLimiter(anyString())).thenThrow(new IllegalStateException("offline"));
        var service=new RequestQuotaService(redis,new MockEnvironment().withProperty("quota.model.llm.concurrency","1"));
        assertThatThrownBy(()->service.model("llm")).isInstanceOf(BusinessException.class);
        var limiter=mock(RRateLimiter.class);when(limiter.tryAcquire()).thenReturn(true);
        doReturn(limiter).when(redis).getRateLimiter(anyString());
        try(var permit=service.model("llm")) { assertThat(permit).isNotNull(); }
    }
}
