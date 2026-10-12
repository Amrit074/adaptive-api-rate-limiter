package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.model.ApiClient;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ClientPolicyRedisTest {
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
 LettuceConnectionFactory connection; PolicyLimiter limiter; ApiClient client;
 @BeforeEach void setup(){connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));connection.afterPropertiesSet();limiter=new PolicyLimiter(new StringRedisTemplate(connection));client=new ApiClient();client.setId(UUID.randomUUID().toString());client.setBurstLimit(3);client.setBurstSeconds(1);client.setSustainedLimit(4);client.setSustainedSeconds(60);}
 @AfterEach void close(){connection.destroy();}
 @Test void bothWindowsEnforceAndRejectedCallsDoNotConsumePermits() throws Exception {
  for(int i=0;i<3;i++)assertThat(limiter.check(client,"test").allowed()).isTrue();
  assertThat(limiter.check(client,"test").reason()).isEqualTo("BURST_LIMIT");
  Thread.sleep(1100);
  assertThat(limiter.check(client,"test").allowed()).isTrue();
  var rejected=limiter.check(client,"test");assertThat(rejected.reason()).isEqualTo("SUSTAINED_LIMIT");assertThat(rejected.retryAfterSeconds()).isPositive();
 }
 @Test void concurrentRequestsCannotOverrunClientQuota() throws Exception {
  client.setBurstLimit(10);client.setBurstSeconds(60);client.setSustainedLimit(20);client.setSustainedSeconds(120);
  var pool=Executors.newFixedThreadPool(20);var tasks=new java.util.ArrayList<Callable<Boolean>>();
  for(int i=0;i<100;i++)tasks.add(()->limiter.check(client,"test").allowed());
  try{long allowed=0;for(var result:pool.invokeAll(tasks))if(result.get())allowed++;assertThat(allowed).isEqualTo(10);}finally{pool.shutdownNow();}
 }
 @Test void reducingLimitUsesEnoughExpirationsForRetry(){
  for(int i=0;i<3;i++)limiter.check(client,"test");client.setBurstLimit(1);
  var result=limiter.check(client,"test");assertThat(result.allowed()).isFalse();assertThat(result.retryAfterSeconds()).isPositive();
 }
}
