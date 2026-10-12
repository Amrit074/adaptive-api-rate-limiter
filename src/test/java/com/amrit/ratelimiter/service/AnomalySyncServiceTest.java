package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.model.AnomalyResult;
import com.amrit.ratelimiter.repository.AnomalyResultRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

class AnomalySyncServiceTest {
    @Test void failedRedisWriteRemainsPendingAndCanBeRetried() {
        var repository = mock(AnomalyResultRepository.class);
        var limiter = mock(RateLimiterService.class);
        var row = new AnomalyResult();
        row.setClientIp("test-ip");
        row.setAnomalyScore(-0.5);
        row.setSyncedToRedis(false);
        when(repository.findBySyncedToRedisAndIsAnomalous(false, true)).thenReturn(List.of(row));
        doThrow(new IllegalStateException("Redis offline")).doNothing().when(limiter).blockIp(eq("test-ip"), anyString());
        var service = new AnomalySyncService(repository, limiter);
        service.syncAnomalyBlocklist();
        assertThat(row.getSyncedToRedis()).isFalse();
        verify(repository, never()).save(any());
        service.syncAnomalyBlocklist();
        assertThat(row.getSyncedToRedis()).isTrue();
        verify(repository).save(row);
    }
}
