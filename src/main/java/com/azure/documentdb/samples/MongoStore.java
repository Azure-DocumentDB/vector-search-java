package com.azure.documentdb.samples;

import com.mongodb.MongoCommandException;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.InsertManyOptions;
import org.bson.Document;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class MongoStore implements Comparison.Store {
    private final Config config;
    private final Connections connections;
    private final MongoDatabase database;
    MongoStore(Config config, Connections connections) {
        this(config, connections, connections.mongo().getDatabase(config.database()));
    }
    MongoStore(Config config, Connections connections, MongoDatabase database) {
        this.config = config; this.connections = connections; this.database = database;
    }
    @Override public Comparison.Counts prepare(Comparison.Case c, Fixture fixture) {
        MongoCollection<Document> collection = database.getCollection(config.collection(c));
        Document wanted = index(config, c);
        // Check the actual definition, regardless of data count. Never replace/drop any index.
        boolean reused = checkIndex(collection, wanted, config.field());
        List<Object> ids = fixture.documents().stream().map(d -> d.get("_id")).toList();
        Map<Object, Document> existing = new HashMap<>();
        try (MongoCursor<Document> cursor = collection.find(Filters.in("_id", ids)).iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next(); existing.put(doc.get("_id"), doc);
            }
        }
        List<Document> missing = new ArrayList<>();
        // Validate every existing fixture record before any insert or index creation in this case.
        for (Document expected : fixture.documents()) {
            Document actual = existing.get(expected.get("_id"));
            if (actual == null) missing.add(new Document(expected));
            else if (!expected.equals(actual)) throw new IllegalStateException("fixture ID conflict");
        }
        if (!missing.isEmpty()) collection.insertMany(missing, new InsertManyOptions().ordered(true));
        // Partial insertion errors propagate. A later retry verifies contents and inserts only missing IDs.
        long fixtureCount = collection.countDocuments(Filters.in("_id", ids));
        if (fixtureCount != fixture.documents().size()) throw new IllegalStateException("fixture count mismatch");
        if (!reused) database.runCommand(new Document("createIndexes", config.collection(c))
                .append("indexes", List.of(wanted)));
        if (!checkIndex(collection, wanted, config.field())) throw new IllegalStateException("index not visible");
        return new Comparison.Counts(collection.countDocuments(), fixtureCount, missing.size(), reused);
    }
    static boolean checkIndex(MongoCollection<Document> collection, Document wanted, String field) {
        List<Document> indexes = new ArrayList<>();
        try (MongoCursor<Document> cursor = collection.listIndexes().iterator()) {
            while (cursor.hasNext()) indexes.add(cursor.next());
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 26) throw e; // Only NamespaceNotFound means no indexes.
        }
        return compatibleIndexes(indexes, wanted, field);
    }
    static boolean compatibleIndexes(List<Document> indexes, Document wanted, String field) {
        boolean exists = false;
        for (Document actual : indexes) {
            Document key = actual.get("key", Document.class);
            // Inspect the FULL catalog, including otherwise unrelated indexes. Unique/TTL/
            // filtered/hidden indexes can alter insertion, retention or visibility semantics.
            if (actual.containsKey("partialFilterExpression") || actual.containsKey("expireAfterSeconds")
                    || enabledOrMalformed(actual, "sparse") || enabledOrMalformed(actual, "hidden")
                    || (enabledOrMalformed(actual, "unique") && !isIdIndex(actual)))
                throw new IllegalStateException("unsafe existing index options");
            boolean sameName = wanted.get("name").equals(actual.get("name"));
            boolean sameVectorField = key != null && "cosmosSearch".equals(key.get(field));
            if (!sameName && !sameVectorField) continue;
            if (exists || !sameName || !wanted.get("key").equals(key)) throw new IllegalStateException("index conflict");
            Document options = actual.get("cosmosSearchOptions", Document.class);
            Document expected = wanted.get("cosmosSearchOptions", Document.class);
            if (options == null) throw new IllegalStateException("index options missing");
            for (String name : expected.keySet()) {
                Object a = options.get(name), b = expected.get(name);
                boolean match = a instanceof Number n && b instanceof Number m
                        ? Double.compare(n.doubleValue(), m.doubleValue()) == 0 : Objects.equals(a, b);
                if (!match) throw new IllegalStateException("index option conflict");
            }
            // Reject unknown algorithm options rather than silently treating a different
            // definition as compatible. Catalog metadata (v/ns/background) remains harmless.
            if (!options.keySet().equals(expected.keySet())) throw new IllegalStateException("unknown index options");
            if (actual.containsKey("collation")) throw new IllegalStateException("index collation conflict");
            exists = true;
        }
        return exists;
    }
    private static boolean enabledOrMalformed(Document index, String option) {
        return index.containsKey(option) && !Boolean.FALSE.equals(index.get(option));
    }
    private static boolean isIdIndex(Document index) {
        return "_id_".equals(index.get("name")) && new Document("_id", 1).equals(index.get("key"));
    }
    static Document index(Config config, Comparison.Case c) {
        Document options = new Document("dimensions", config.dimensions()).append("similarity", c.metric())
                .append("kind", "vector-" + c.algorithm());
        switch (c.algorithm()) {
            case "ivf" -> options.append("numLists", 1);
            case "hnsw" -> options.append("m", 16).append("efConstruction", 64);
            case "diskann" -> options.append("maxDegree", 32).append("lBuild", 50);
            default -> throw new IllegalArgumentException("unknown algorithm");
        }
        return new Document("name", "vector_" + c.algorithm() + "_" + c.metric().toLowerCase(Locale.ROOT))
                .append("key", new Document(config.field(), "cosmosSearch")).append("cosmosSearchOptions", options);
    }
    @Override public List<Document> search(Comparison.Case c, List<Double> query) {
        Document search = new Document("$search", new Document("cosmosSearch", new Document("vector", query)
                .append("path", config.field()).append("k", config.topK())));
        Document project = new Document("$project", new Document("_id", 1).append("HotelId", 1)
                .append("HotelName", 1).append("score", new Document("$meta", "searchScore")));
        List<Document> hits = new ArrayList<>();
        try (MongoCursor<Document> cursor = database.getCollection(config.collection(c)).aggregate(List.of(search, project))
                .maxTime(config.timeoutSeconds(), TimeUnit.SECONDS).iterator()) {
            while (cursor.hasNext()) hits.add(cursor.next());
        }
        return hits;
    }
    @Override public void close() { connections.close(); }
}
