package com.example.server.ratelimit;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import org.redisson.api.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.*;

/** Redis RPM limits are shared across instances; semaphores bound local model concurrency. */
@Service
public class RequestQuotaService {
    private final RedissonClient redis;
    private final Environment config;
    private final Map<String, Semaphore> concurrency = new ConcurrentHashMap<>();
    public RequestQuotaService(RedissonClient redis, Environment config) { this.redis=redis; this.config=config; }
    public void entry(String business, Long userId) {
        if(userId==null) throw new BusinessException(ErrorCode.UNAUTHORIZED,"请先登录");
        long userDefault=business.equals("parse")?3:10;
        acquire("entry:"+business+":user:"+userId, setting("entry."+business+".user-rpm",userDefault));
        acquire("entry:"+business+":global", setting("entry."+business+".global-rpm",business.equals("parse")?30:100));
    }
    public Permit model(String type) {
        Semaphore semaphore=concurrency.computeIfAbsent(type, key->new Semaphore(Math.toIntExact(setting("model."+key+".concurrency",key.equals("vision")?2:4))));
        if(!semaphore.tryAcquire()) throw new BusinessException(ErrorCode.RATE_LIMITED,"模型并发繁忙，请稍后重试");
        try {
            acquire("model:"+type,setting("model."+type+".rpm",type.equals("embedding")?120:60));
            return new Permit(semaphore);
        } catch(RuntimeException error) { semaphore.release(); throw error; }
    }
    private long setting(String key,long fallback) {
        long value=config.getProperty("quota."+key,Long.class,fallback);
        if(value<1) throw new IllegalArgumentException("Invalid quota."+key);
        return value;
    }
    private void acquire(String key,long rpm) {
        try {
            RRateLimiter limiter=redis.getRateLimiter("quota:v1:"+key+":"+rpm);
            limiter.trySetRate(RateType.OVERALL,rpm,1,RateIntervalUnit.MINUTES);
            // Idle user keys expire; continuing traffic refreshes expiration.
            limiter.expire(java.time.Duration.ofHours(1));
            if(!limiter.tryAcquire()) throw new BusinessException(ErrorCode.RATE_LIMITED,"请求过于频繁，请稍后重试");
        } catch(BusinessException error) { throw error; }
        catch(RuntimeException error) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"限流服务暂不可用，请稍后重试"); }
    }
    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        Permit(Semaphore semaphore) { this.semaphore=semaphore; }
        @Override public void close() { if(closed.compareAndSet(false,true)) semaphore.release(); }
    }
}
