package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.model.ApiClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.*;

@Service
public class PolicyLimiter {
    private final RedisTemplate<String,String> redis;
    public PolicyLimiter(@Qualifier("rateLimitTemplate") RedisTemplate<String,String> redis) { this.redis=redis; }
    // Both windows are checked before either receives a permit. TIME is Redis's clock.
    static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
        local t = redis.call('TIME')
        local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
        local limits = {tonumber(ARGV[1]), tonumber(ARGV[3])}
        local windows = {tonumber(ARGV[2])*1000, tonumber(ARGV[4])*1000}
        local counts = {}
        local retry = 0
        local binding = 1
        for i=1,2 do
          redis.call('ZREMRANGEBYSCORE',KEYS[i],'-inf',now-windows[i])
          counts[i] = redis.call('ZCARD',KEYS[i])
          if counts[i] >= limits[i] then
            local requiredIndex = counts[i]-limits[i]
            local oldest = redis.call('ZRANGE',KEYS[i],requiredIndex,requiredIndex,'WITHSCORES')
            local wait = math.max(1,math.ceil((tonumber(oldest[2])+windows[i]-now)/1000))
            if wait > retry then retry=wait; binding=i end
          end
        end
        if retry > 0 then return {0,counts[binding],limits[binding],retry,binding} end
        for i=1,2 do
          redis.call('ZADD',KEYS[i],now,ARGV[5])
          redis.call('PEXPIRE',KEYS[i],windows[i])
        end
        if limits[2]-counts[2] < limits[1]-counts[1] then binding=2 end
        return {1,counts[binding]+1,limits[binding],0,binding}
        """, List.class);
    public RateLimiterService.RateLimitResult check(ApiClient client, String ip) {
        try {
            String blocked = redis.opsForValue().get("block:"+ip);
            if (blocked != null) return RateLimiterService.RateLimitResult.blocked(0,client.getBurstLimit(),client.getBurstSeconds(),"ANOMALY_BLOCK: "+blocked);
            // Stable keys keep already-consumed permits when a policy changes or a key rotates.
            String prefix="policy:{"+client.getId()+"}:";
            List<?> r=redis.execute(SCRIPT,List.of(prefix+"burst",prefix+"sustained"),
                ""+client.getBurstLimit(),""+client.getBurstSeconds(),""+client.getSustainedLimit(),""+client.getSustainedSeconds(),UUID.randomUUID().toString());
            if(r==null || r.size()!=5) throw new IllegalStateException("Missing Redis decision");
            int count=((Number)r.get(1)).intValue(), limit=((Number)r.get(2)).intValue();
            boolean burst=((Number)r.get(4)).intValue()==1;
            if(((Number)r.get(0)).intValue()==0) return RateLimiterService.RateLimitResult.rateLimited(count,limit,((Number)r.get(3)).intValue(),burst?"BURST_LIMIT":"SUSTAINED_LIMIT");
            return RateLimiterService.RateLimitResult.allowed(count,limit,burst?client.getBurstSeconds():client.getSustainedSeconds());
        } catch(Exception e) {
            return RateLimiterService.RateLimitResult.failOpen(client.getBurstLimit(),client.getBurstSeconds(),"Redis unavailable; quota enforcement temporarily unavailable");
        }
    }
}
