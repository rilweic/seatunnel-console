package com.dataintegration.console.engine;

/**
 * Definition of one connector option as the engine describes it (not a value): used to render the
 * wizard's advanced options.
 *
 * @param type engine type name, e.g. {@code java.lang.Integer} or an enum class
 * @param defaultValue rendered as text; null when there is none
 */
public record ConnectorOption(String key, String type, String defaultValue, String description, boolean required) {}
