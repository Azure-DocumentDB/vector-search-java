package com.azure.documentdb.samples;

import org.bson.Document;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.io.IOException;

record Fixture(List<Document> documents, List<Double> query) {
    static Fixture load(Config config) {
        try {
            List<Document> input = Document.parse("{\"data\":" + read(config.dataFile()) + "}").getList("data", Document.class);
            if (input == null || input.isEmpty() || input.size() > 10000) throw new IllegalArgumentException("invalid fixture size");
            Set<String> ids = new HashSet<>();
            List<Document> documents = new ArrayList<>();
            for (Document source : input) {
                String id = source.getString("HotelId");
                if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id))
                    throw new IllegalArgumentException("missing or duplicate HotelId");
                Document doc = new Document(source);
                doc.put("_id", "hotel:" + id);
                doc.put(config.field(), vector(source.get(config.field()), config.dimensions()));
                documents.add(doc);
            }
            List<Double> query = vector(Document.parse("{\"vector\":" + read(config.queryFile()) + "}").get("vector"), config.dimensions());
            if (config.topK() > documents.size()) throw new IllegalArgumentException("TOP_K exceeds fixture size");
            return new Fixture(List.copyOf(documents), query);
        } catch (IOException | RuntimeException e) {
            // Parser/file exceptions may contain user data or paths; don't echo them.
            throw new IllegalArgumentException("invalid fixture or query vector; check files, HotelId, dimensions and TOP_K");
        }
    }
    private static String read(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 64L * 1024 * 1024) throw new IOException("invalid file");
        return Files.readString(path);
    }
    static List<Double> vector(Object value, int dimensions) {
        if (!(value instanceof List<?> list) || list.size() != dimensions) throw new IllegalArgumentException("vector dimensions");
        List<Double> vector = new ArrayList<>();
        double norm = 0;
        for (Object component : list) {
            if (!(component instanceof Number number) || !Double.isFinite(number.doubleValue()))
                throw new IllegalArgumentException("vector components must be finite numbers");
            double d = number.doubleValue();
            vector.add(d); norm += d * d;
        }
        if (!Double.isFinite(norm) || norm == 0) throw new IllegalArgumentException("invalid vector norm");
        return List.copyOf(vector);
    }
}
