package com.agentgate.mocktools.chaos;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs before every /api/** tool call and injects the configured delay and random failures.
 * Doing it here (not inside each controller method) keeps the tool code clean.
 */
public class ChaosInterceptor implements HandlerInterceptor {

    private final ChaosState chaosState;

    public ChaosInterceptor(ChaosState chaosState) {
        this.chaosState = chaosState;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws InterruptedException {
        ChaosSettings settings = chaosState.get();
        if (settings.delayMs() > 0) {
            Thread.sleep(settings.delayMs()); // cheap with virtual threads
        }
        if (settings.failRate() > 0 && ThreadLocalRandom.current().nextDouble() < settings.failRate()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated failure (chaos mode)");
        }
        return true;
    }
}
