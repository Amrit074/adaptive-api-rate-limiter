package com.amrit.ratelimiter.repository;
import com.amrit.ratelimiter.model.ApiClient;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface ApiClientRepository extends JpaRepository<ApiClient, String> {
    Optional<ApiClient> findByKeyHash(String keyHash);
}
