package com.github.rmannibucau.javaagent.filtering;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

import java.lang.instrument.Instrumentation;
import java.util.AbstractMap;
import java.util.Map;
import java.util.stream.Stream;

public class FilteringAgent {
    private FilteringAgent() {
        // no-op
    }

    public static void premain(final String agentArgs, final Instrumentation instrumentation) {
        instrumentation.addTransformer(new FilteringTransformer(parseArgs(sanitize(agentArgs))));
    }

    private static String sanitize(final String agentArgs) { // support env vars
        if (agentArgs.startsWith("${") && agentArgs.endsWith("}")) {
            final int defaultValStart = agentArgs.indexOf(":-");
            if (defaultValStart > 0) {
                final String env = agentArgs.substring("${".length(), defaultValStart);
                return ofNullable(System.getenv(env))
                        .orElseGet(() -> agentArgs.substring(defaultValStart + ":-".length(), agentArgs.length() - "}".length()));
            }
        }
        return agentArgs;
    }

    // arg1=val1|arg2=val2 format
    private static Map<String, String> parseArgs(final String agentArgs) {
        return Stream.of(agentArgs.split("\\|"))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .map(it -> {
                    final int eq = it.indexOf("=");
                    if (eq > 0) {
                        return new AbstractMap.SimpleEntry<>(it.substring(0, eq), it.substring(eq + 1));
                    }
                    return new AbstractMap.SimpleEntry<>(it, "true");
                })
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
