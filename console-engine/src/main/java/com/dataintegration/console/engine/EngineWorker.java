package com.dataintegration.console.engine;

/** A worker node and its resources, as the engine reports them. */
public record EngineWorker(
        String address,
        long totalHeapBytes,
        long availableHeapBytes,
        int totalSlots,
        int freeSlots,
        boolean dynamicSlot,
        int runningJobs) {}
