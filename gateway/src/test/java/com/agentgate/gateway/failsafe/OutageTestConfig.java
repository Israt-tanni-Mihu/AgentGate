package com.agentgate.gateway.failsafe;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Test tools for QA4. A real "docker stop postgres" is done in the demo; in tests we switch the database off at the
 * only place that matters, the connection: while {@link #DOWN} is true, nobody can get a connection, exactly like
 * when PostgreSQL is not running.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OutageTestConfig {

    static final AtomicBoolean DOWN = new AtomicBoolean();
    static final MovableClock CLOCK = new MovableClock();

    static void databaseDown() {
        DOWN.set(true);
    }

    static void databaseUp() {
        DOWN.set(false);
    }

    @Bean
    static BeanPostProcessor outageSwitch() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource ds && !(bean instanceof OutageDataSource)
                        ? new OutageDataSource(ds) : bean;
            }
        };
    }

    /** The clock the whole gateway uses in these tests, so a test can "wait 6 minutes" without waiting. */
    @Bean
    @Primary
    Clock movableClock() {
        return CLOCK;
    }

    static class OutageDataSource extends DelegatingDataSource {
        OutageDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            failIfDown();
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            failIfDown();
            return super.getConnection(username, password);
        }

        private static void failIfDown() throws SQLException {
            if (DOWN.get()) {
                throw new SQLTransientConnectionException("simulated outage: connection refused");
            }
        }
    }

    static class MovableClock extends Clock {
        private volatile Duration offset = Duration.ZERO;

        void advance(Duration by) {
            offset = offset.plus(by);
        }

        void reset() {
            offset = Duration.ZERO;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset);
        }
    }
}
