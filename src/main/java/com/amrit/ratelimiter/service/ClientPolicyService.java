package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.model.ApiClient;
import com.amrit.ratelimiter.repository.ApiClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class ClientPolicyService {
    private final ApiClientRepository repository;
    public ClientPolicyService(ApiClientRepository repository) { this.repository = repository; }
    public record PolicyInput(String name, int burstLimit, int burstSeconds, int sustainedLimit, int sustainedSeconds, boolean enabled) {
        public void validate() {
            if (name == null || name.isBlank() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl) || burstLimit < 1 || burstLimit > 10000 ||
                sustainedLimit < burstLimit || sustainedLimit > 10000 || burstSeconds < 1 ||
                sustainedSeconds <= burstSeconds || sustainedSeconds > 3600)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a name, limits 1–10000, and windows 1–3600s; sustained window must be longer and limit at least burst limit.");
        }
    }
    public record Created(ApiClient client, String apiKey) {}
    public List<ApiClient> list() { return repository.findAll(); }
    public ApiClient authenticate(String key) {
        if (key.length() > 200) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key");
        return repository.findByKeyHash(hash(key)).filter(ApiClient::isEnabled)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or disabled API key"));
    }
    @Transactional public Created create(PolicyInput input) {
        input.validate();
        var client = new ApiClient(); client.setId(UUID.randomUUID().toString());
        apply(client, input);
        String key = newKey(); client.setKeyHash(hash(key));
        return new Created(repository.saveAndFlush(client), key);
    }
    @Transactional public ApiClient update(String id, PolicyInput input) {
        input.validate(); var client = find(id);
        if (input.burstSeconds() != client.getBurstSeconds() || input.sustainedSeconds() != client.getSustainedSeconds())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Window durations are fixed for a client. Create a new client to change them; limits and enabled status remain editable.");
        apply(client, input);
        return repository.saveAndFlush(client);
    }
    @Transactional public Created rotate(String id) {
        var client = find(id); String key = newKey(); client.setKeyHash(hash(key));
        return new Created(repository.saveAndFlush(client), key);
    }
    private ApiClient find(String id) { return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found")); }
    private void apply(ApiClient c, PolicyInput p) {
        c.setName(p.name().trim()); c.setBurstLimit(p.burstLimit()); c.setBurstSeconds(p.burstSeconds());
        c.setSustainedLimit(p.sustainedLimit()); c.setSustainedSeconds(p.sustainedSeconds()); c.setEnabled(p.enabled());
    }
    private static String newKey() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return "rl_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
