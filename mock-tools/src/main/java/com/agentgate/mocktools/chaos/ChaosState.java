package com.agentgate.mocktools.chaos;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Holds the current chaos settings. AtomicReference makes updates visible to all request threads at once. */
@Component
public class ChaosState {

    private final AtomicReference<ChaosSettings> current = new AtomicReference<>(ChaosSettings.NONE);

    public ChaosSettings get() {
        return current.get();
    }

    public void set(ChaosSettings settings) {
        current.set(settings);
    }

    public void reset() {
        current.set(ChaosSettings.NONE);
    }
}
