package com.azure.documentdb.samples;

import org.bson.Document;
import java.util.*;

/** Small-fixture cosine correctness oracle, not a large-dataset ANN recall benchmark. */
final class Ranking {
    static void verify(Fixture fixture, List<Document> hits, String field, int topK) {
        Comparison.validateHits(hits, topK);
        Map<Object, Double> scores = new HashMap<>();
        for (Document doc : fixture.documents())
            scores.put(doc.get("_id"), cosine(fixture.query(), Fixture.vector(doc.get(field), fixture.query().size())));
        List<Double> expected = scores.values().stream().sorted(Comparator.reverseOrder()).limit(topK).toList();
        for (int rank = 0; rank < topK; rank++) {
            Document hit = hits.get(rank);
            Double exact = scores.get(hit.get("_id"));
            if (exact == null || Math.abs(exact - expected.get(rank)) > 1e-6
                    || Math.abs(((Number) hit.get("score")).doubleValue() - exact) > 1e-4)
                throw new IllegalStateException("fixture rank or cosine score mismatch");
        }
        boolean selfQuery = fixture.documents().stream().anyMatch(d -> fixture.query().equals(d.get(field)));
        if (selfQuery && Math.abs(scores.get(hits.getFirst().get("_id")) - 1.0) > 1e-6)
            throw new IllegalStateException("self-match missing");
    }
    static double cosine(List<Double> a, List<Double> b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i); normA += a.get(i) * a.get(i); normB += b.get(i) * b.get(i);
        }
        double result = dot / Math.sqrt(normA) / Math.sqrt(normB);
        if (!Double.isFinite(result)) throw new IllegalStateException("invalid cosine score");
        return result;
    }
}
