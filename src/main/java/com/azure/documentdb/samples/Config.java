package com.azure.documentdb.samples;

import java.nio.file.Path;
import java.util.Map;
import java.util.Locale;

/** Validated before constructing any network or credential client. No arbitrary URIs. */
record Config(String host, String database, String prefix, String auth, String username,
              String password, Path dataFile, Path queryFile, String field, int dimensions,
              int topK, int timeoutSeconds, int attempts, int retryDelayMs, boolean verifyFixtureRanking) {
    static Config from(Map<String, String> env) {
        String host = required(env, "DOCUMENTDB_HOST");
        validateHost(host);
        String database = name(required(env, "DOCUMENTDB_DATABASE"), "database", 48);
        if (java.util.Set.of("admin", "config", "local").contains(database.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("reserved database");
        String prefix = name(required(env, "DOCUMENTDB_COLLECTION_PREFIX"), "collection prefix", 48);
        if (!"true".equals(env.get("ALLOW_DATABASE_WRITES")))
            throw new IllegalArgumentException("ALLOW_DATABASE_WRITES must be exactly true");
        String auth = required(env, "DOCUMENTDB_AUTH");
        String user = null, password = null;
        if ("native".equals(auth)) {
            user = required(env, "DOCUMENTDB_USERNAME");
            password = env.get("DOCUMENTDB_PASSWORD");
            if (password == null || password.isBlank()) throw new IllegalArgumentException("required setting: DOCUMENTDB_PASSWORD");
        } else if ("oidc".equals(auth)) {
            if (env.containsKey("DOCUMENTDB_USERNAME") || env.containsKey("DOCUMENTDB_PASSWORD"))
                throw new IllegalArgumentException("unset native credentials for oidc");
        } else throw new IllegalArgumentException("DOCUMENTDB_AUTH must be native or oidc");
        return new Config(host, database, prefix, auth, user, password,
                Path.of(required(env, "DATA_FILE_WITH_VECTORS")), Path.of(required(env, "QUERY_VECTOR_FILE")),
                name(env.getOrDefault("EMBEDDED_FIELD", "DescriptionVector"), "vector field", 64),
                integer(env, "EMBEDDING_DIMENSIONS", 1536, 2, 2000), integer(env, "TOP_K", 5, 1, 100),
                integer(env, "OPERATION_TIMEOUT_SECONDS", 20, 1, 60), integer(env, "SEARCH_ATTEMPTS", 4, 1, 6),
                integer(env, "SEARCH_RETRY_DELAY_MS", 1000, 0, 5000), bool(env, "VERIFY_FIXTURE_RANKING"));
    }

    private static boolean bool(Map<String, String> env, String key) {
        String value = env.getOrDefault(key, "false");
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("invalid boolean setting");
        return value.equals("true");
    }
    static void validateHost(String host) {
        if (host == null || !host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.(?:global\\.mongocluster\\.cosmos\\.azure\\.com|mongocluster\\.cosmos\\.azure\\.com|global\\.mongocluster\\.azure\\.com|mongocluster\\.azure\\.com)"))
            throw new IllegalArgumentException("DOCUMENTDB_HOST must be an Azure DocumentDB SRV hostname");
    }
    private static String name(String value, String label, int max) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]{0," + (max - 1) + "}"))
            throw new IllegalArgumentException("invalid " + label);
        return value;
    }
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank() || value.contains("<") || value.contains(">"))
            throw new IllegalArgumentException("required setting: " + key);
        return value;
    }
    private static int integer(Map<String, String> env, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(env.getOrDefault(key, Integer.toString(fallback)));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("invalid numeric setting: " + key); }
    }
    String collection(Comparison.Case c) { return prefix + "_" + c.algorithm() + "_" + c.metric().toLowerCase(Locale.ROOT); }
    @Override public String toString() { return "Config[redacted]"; }
}
