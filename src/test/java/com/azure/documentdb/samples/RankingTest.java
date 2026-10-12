package com.azure.documentdb.samples;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RankingTest {
    static Fixture fixture() {
        return new Fixture(List.of(
                doc("1", List.of(1.0, 0.0)), doc("2", List.of(0.8, 0.6)),
                doc("3", List.of(0.0, 1.0))), List.of(1.0, 0.0));
    }
    static Document doc(String id, List<Double> vector) {
        return new Document("_id", "hotel:" + id).append("DescriptionVector", vector);
    }
    static Document hit(String id, double score) { return new Document("_id", "hotel:" + id).append("score", score); }
    @Test void knownCosineRanksAndSelfMatch() {
        assertEquals(0.8, Ranking.cosine(List.of(1.0, 0.0), List.of(0.8, 0.6)), 1e-12);
        Ranking.verify(fixture(), List.of(hit("1", 1), hit("2", .8)), "DescriptionVector", 2);
    }
    @Test void wrongRanksScoresUnknownRecordsAndMissingSelfFail() {
        for (List<Document> bad : List.of(List.of(hit("2", .8), hit("1", 1)),
                List.of(hit("1", .5), hit("2", .8)), List.of(hit("1", 1), hit("unknown", .8)),
                List.of(hit("2", .8), hit("3", 0))))
            assertThrows(IllegalStateException.class, () -> Ranking.verify(fixture(), bad, "DescriptionVector", 2));
    }
}
