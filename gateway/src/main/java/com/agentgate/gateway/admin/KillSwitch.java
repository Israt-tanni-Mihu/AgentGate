package com.agentgate.gateway.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/** One row of the "kill_switches" table (Flyway V4). engaged = false keeps the row as history. */
@Entity
@Table(name = "kill_switches")
public class KillSwitch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KillSwitchScope scope;

    /** Agent client id, tool name, or {@link #ALL_TARGET} for ALL_WRITES. */
    @Column(nullable = false, length = 100)
    private String target;

    @Column(nullable = false)
    private boolean engaged;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Target used for the ALL_WRITES switch (it has no name of its own). */
    public static final String ALL_TARGET = "*";

    protected KillSwitch() {
        // for JPA
    }

    public KillSwitch(KillSwitchScope scope, String target) {
        this.scope = scope;
        this.target = target;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public KillSwitchScope getScope() { return scope; }
    public String getTarget() { return target; }
    public boolean isEngaged() { return engaged; }
    public void setEngaged(boolean engaged) { this.engaged = engaged; }
    public Instant getUpdatedAt() { return updatedAt; }
}
