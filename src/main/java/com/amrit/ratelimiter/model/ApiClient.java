package com.amrit.ratelimiter.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Entity @Data
public class ApiClient {
    @Id private String id;
    private String name;
    @JsonIgnore @Column(unique=true, nullable=false) private String keyHash;
    private int burstLimit;
    private int burstSeconds;
    private int sustainedLimit;
    private int sustainedSeconds;
    private boolean enabled = true;
    @Version private long version;
}
