package com.dataintegration.console.engine;

/**
 * One job log file on one engine node.
 *
 * @param node engine node that wrote it, e.g. {@code 10.0.0.5:8080}
 * @param name file name, e.g. {@code job-1166038663348355073.log}
 * @param location engine-specific address used by {@link EngineClient#readLog}
 */
public record EngineLogFile(String node, String name, String location) {}
