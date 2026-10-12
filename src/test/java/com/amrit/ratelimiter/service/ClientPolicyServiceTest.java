package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.model.ApiClient;
import com.amrit.ratelimiter.repository.ApiClientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientPolicyServiceTest {
    @Test void storesOnlyHashAndRejectsDisabledKey() {
        var repo=mock(ApiClientRepository.class);
        when(repo.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
        var service=new ClientPolicyService(repo);
        var result=service.create(new ClientPolicyService.PolicyInput("Web",5,10,30,60,true));
        assertThat(result.client().getKeyHash()).isEqualTo(ClientPolicyService.hash(result.apiKey()));
        assertThat(result.client().getKeyHash()).doesNotContain(result.apiKey());
        when(repo.findByKeyHash(result.client().getKeyHash())).thenReturn(Optional.of(result.client()));
        assertThat(service.authenticate(result.apiKey())).isSameAs(result.client());
        result.client().setEnabled(false);
        assertThatThrownBy(()->service.authenticate(result.apiKey())).isInstanceOf(ResponseStatusException.class);
    }
    @Test void durationChangesAndControlCharactersAreRejected() {
        var repo=mock(ApiClientRepository.class); var client=new ApiClient();
        client.setBurstSeconds(10);client.setSustainedSeconds(60);
        when(repo.findById("id")).thenReturn(Optional.of(client));
        var service=new ClientPolicyService(repo);
        assertThatThrownBy(()->service.update("id",new ClientPolicyService.PolicyInput("Web",5,20,30,60,true))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.create(new ClientPolicyService.PolicyInput("Web\r\nInjected",5,10,30,60,true))).isInstanceOf(ResponseStatusException.class);
        verify(repo,never()).saveAndFlush(any());
    }
}
