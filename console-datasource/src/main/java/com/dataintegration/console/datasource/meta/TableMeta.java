package com.dataintegration.console.datasource.meta;

import java.util.List;

/** A table (or ES index) a job can read or write; {@code primaryKeys} is empty when it has none. */
public record TableMeta(String name, List<String> primaryKeys) {}
