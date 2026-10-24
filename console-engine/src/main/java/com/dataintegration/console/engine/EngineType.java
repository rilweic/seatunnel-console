package com.dataintegration.console.engine;

/** Execution engines. Only ZETA is implemented in P1/P2; FLINK and SPARK are planned for P3. */
public enum EngineType {
    ZETA,
    FLINK,
    SPARK
}
