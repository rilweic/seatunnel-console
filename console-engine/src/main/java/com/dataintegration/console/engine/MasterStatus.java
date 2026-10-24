package com.dataintegration.console.engine;

/**
 * One configured master, probed on its own (no failover).
 *
 * @param authRequired whether an unauthenticated request is refused; null when it could not be told
 * @param version engine version the master reports, e.g. {@code 3.0.0}
 * @param error why it is unreachable or unusable; null when healthy
 */
public record MasterStatus(String url, boolean reachable, Boolean authRequired, String version, long latencyMillis, String error) {}
