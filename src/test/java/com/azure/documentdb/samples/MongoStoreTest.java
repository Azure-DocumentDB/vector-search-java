package com.azure.documentdb.samples;

import com.mongodb.*;
import com.mongodb.client.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MongoStoreTest {
    @SuppressWarnings("unchecked")
    static <T> MongoCursor<T> cursor(List<T> items) {
        MongoCursor<T> cursor = mock(MongoCursor.class);
        Iterator<T> iterator = items.iterator();
        when(cursor.hasNext()).thenAnswer(x -> iterator.hasNext());
        when(cursor.next()).thenAnswer(x -> iterator.next());
        return cursor;
    }
    @Test void existingDefinitionsVerifiedNotIgnoredByNameOrDataCount() {
        Config config = SafetyTest.config(); var c = Comparison.cases().getFirst(); var wanted = MongoStore.index(config, c);
        assertTrue(MongoStore.compatibleIndexes(List.of(wanted), wanted, config.field()));
        assertFalse(MongoStore.compatibleIndexes(List.of(new Document("name", "_id_").append("key", new Document("_id", 1))), wanted, config.field()));
        for (String property : List.of("dimensions", "similarity", "kind", "numLists")) {
            Document conflict = Document.parse(wanted.toJson()); conflict.get("cosmosSearchOptions", Document.class).put(property, "WRONG");
            assertThrows(IllegalStateException.class, () -> MongoStore.compatibleIndexes(List.of(conflict), wanted, config.field()));
        }
        Document otherName = new Document(wanted).append("name", "another_vector");
        assertThrows(IllegalStateException.class, () -> MongoStore.compatibleIndexes(List.of(otherName), wanted, config.field()));
        Document wrongKey = new Document(wanted).append("key", new Document("OtherField", "cosmosSearch"));
        assertThrows(IllegalStateException.class, () -> MongoStore.compatibleIndexes(List.of(wrongKey), wanted, config.field()));
        Document partial = new Document(wanted).append("partialFilterExpression", new Document("x", 1));
        assertThrows(IllegalStateException.class, () -> MongoStore.compatibleIndexes(List.of(partial), wanted, config.field()));
    }
    @Test void fullCatalogRejectsSemanticallyUnsafeIndexesAndDuplicateDefinitions() {
        Config config = SafetyTest.config(); var wanted = MongoStore.index(config, Comparison.cases().getFirst());
        for (String option : List.of("partialFilterExpression", "sparse", "unique", "expireAfterSeconds", "hidden")) {
            Object value = switch (option) {
                case "partialFilterExpression" -> new Document("Category", "hotel");
                case "expireAfterSeconds" -> 0;
                default -> true;
            };
            for (Document index : List.of(new Document(wanted).append(option, value),
                    new Document("name", "unrelated").append("key", new Document("OtherField", 1)).append(option, value)))
                assertThrows(IllegalStateException.class, () -> MongoStore.compatibleIndexes(List.of(index), wanted, config.field()), option);
        }
        assertThrows(IllegalStateException.class, () -> MongoStore.compatibleIndexes(List.of(wanted, wanted), wanted, config.field()));
        Document extra = Document.parse(wanted.toJson()); extra.get("cosmosSearchOptions", Document.class).put("unexpected", 5);
        assertThrows(IllegalStateException.class, () -> MongoStore.compatibleIndexes(List.of(extra), wanted, config.field()));
        assertTrue(MongoStore.compatibleIndexes(List.of(new Document("name", "_id_").append("key", new Document("_id", 1)).append("unique", true), wanted), wanted, config.field()));
    }
    @SuppressWarnings("unchecked")
    @Test void catalogPermissionAndNetworkFailuresHappenBeforeAnyWrite() {
        Config config = SafetyTest.config(); var c = Comparison.cases().getFirst();
        MongoDatabase db = mock(MongoDatabase.class); MongoCollection<Document> collection = mock(MongoCollection.class);
        when(db.getCollection(config.collection(c))).thenReturn(collection);
        when(collection.listIndexes()).thenThrow(new MongoCommandException(new Document("ok", 0).append("code", 13)
                .append("errmsg", "SECRET catalog error").toBsonDocument(), new ServerAddress("localhost")));
        MongoStore store = new MongoStore(config, mock(Connections.class), db);
        assertThrows(MongoException.class, () -> store.prepare(c, ComparisonTest.fixture()));
        verify(collection, never()).find(any(Bson.class));
        verify(collection, never()).insertMany(anyList(), any()); verify(db, never()).runCommand(any(Bson.class));
    }
    @SuppressWarnings("unchecked")
    @Test void insertOnlyRerunKeepsUnrelatedDataAndCreatesMissingIndex() {
        Config config = SafetyTest.config(); Fixture fixture = ComparisonTest.fixture(); var c = Comparison.cases().getFirst();
        MongoDatabase db = mock(MongoDatabase.class); MongoCollection<Document> collection = mock(MongoCollection.class);
        when(db.getCollection(config.collection(c))).thenReturn(collection);
        ListIndexesIterable<Document> indexes = mock(ListIndexesIterable.class);
        when(collection.listIndexes()).thenReturn(indexes);
        when(indexes.iterator()).thenAnswer(x -> cursor(List.of(MongoStore.index(config, c))));
        FindIterable<Document> find = mock(FindIterable.class); when(collection.find(any(Bson.class))).thenReturn(find);
        when(find.iterator()).thenAnswer(x -> cursor(fixture.documents()));
        when(collection.countDocuments(any(Bson.class))).thenReturn(1L); when(collection.countDocuments()).thenReturn(2L);
        MongoStore store = new MongoStore(config, mock(Connections.class), db);
        var first = store.prepare(c, fixture); var second = store.prepare(c, fixture);
        assertEquals(0, first.inserted()); assertEquals(2, second.total()); assertEquals(1, second.fixture());
        verify(collection, never()).insertMany(anyList(), any()); verify(db, never()).runCommand(any(Bson.class));
        verify(collection, never()).drop(); verify(collection, never()).deleteMany(any()); verify(collection, never()).dropIndex(anyString());
        // Same non-empty data, but no index: index creation must NOT be skipped.
        MongoCursor<Document> missingIndex = cursor(List.of());
        MongoCursor<Document> createdIndex = cursor(List.of(MongoStore.index(config, c)));
        when(indexes.iterator()).thenReturn(missingIndex, createdIndex);
        assertFalse(store.prepare(c, fixture).indexReused());
        verify(db).runCommand(any(Bson.class));
    }
    @SuppressWarnings("unchecked")
    @Test void conflictingDataAndIndexFailBeforeAnyWrites() {
        Config config = SafetyTest.config(); var c = Comparison.cases().getFirst();
        MongoDatabase db = mock(MongoDatabase.class); MongoCollection<Document> collection = mock(MongoCollection.class);
        when(db.getCollection(config.collection(c))).thenReturn(collection);
        ListIndexesIterable<Document> indexes = mock(ListIndexesIterable.class); when(collection.listIndexes()).thenReturn(indexes);
        when(indexes.iterator()).thenAnswer(x -> cursor(List.of()));
        FindIterable<Document> find = mock(FindIterable.class); when(collection.find(any(Bson.class))).thenReturn(find);
        when(find.iterator()).thenAnswer(x -> cursor(List.of(new Document("_id", "hotel:1").append("HotelName", "Unrelated owner"))));
        MongoStore store = new MongoStore(config, mock(Connections.class), db);
        assertThrows(IllegalStateException.class, () -> store.prepare(c, ComparisonTest.fixture()));
        Document wrong = MongoStore.index(config, c).append("name", "existing_owner_index");
        when(indexes.iterator()).thenAnswer(x -> cursor(List.of(wrong)));
        assertThrows(IllegalStateException.class, () -> store.prepare(c, ComparisonTest.fixture()));
        verify(collection, never()).insertMany(anyList(), any()); verify(db, never()).runCommand(any(Bson.class));
        verify(collection, never()).drop(); verify(collection, never()).deleteMany(any());
    }
    @SuppressWarnings("unchecked")
    @Test void insertFailureAndMissingCountAreNotSwallowed() {
        Config config = SafetyTest.config(); var c = Comparison.cases().getFirst();
        MongoDatabase db = mock(MongoDatabase.class); MongoCollection<Document> collection = mock(MongoCollection.class);
        when(db.getCollection(config.collection(c))).thenReturn(collection);
        ListIndexesIterable<Document> indexes = mock(ListIndexesIterable.class); when(collection.listIndexes()).thenReturn(indexes);
        when(indexes.iterator()).thenAnswer(x -> cursor(List.of()));
        FindIterable<Document> find = mock(FindIterable.class); when(collection.find(any(Bson.class))).thenReturn(find);
        when(find.iterator()).thenAnswer(x -> cursor(List.of()));
        MongoStore store = new MongoStore(config, mock(Connections.class), db);
        doThrow(new MongoException(11000, "duplicate key should not be swallowed")).when(collection).insertMany(anyList(), any());
        assertThrows(MongoException.class, () -> store.prepare(c, ComparisonTest.fixture()));
        doReturn(null).when(collection).insertMany(anyList(), any());
        when(collection.countDocuments(any(Bson.class))).thenReturn(0L);
        assertThrows(IllegalStateException.class, () -> store.prepare(c, ComparisonTest.fixture()));
        verify(db, never()).runCommand(any(Bson.class));
    }
    @SuppressWarnings("unchecked")
    @Test void listIndexesErrorsPropagateExceptNamespaceNotFound() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        var wanted = MongoStore.index(SafetyTest.config(), Comparison.cases().getFirst());
        when(collection.listIndexes()).thenThrow(new MongoCommandException(new Document("ok", 0).append("code", 13).append("errmsg", "unauthorized").toBsonDocument(), new ServerAddress("localhost")));
        assertThrows(MongoCommandException.class, () -> MongoStore.checkIndex(collection, wanted, "DescriptionVector"));
        doThrow(new MongoCommandException(new Document("ok", 0).append("code", 26).append("errmsg", "missing").toBsonDocument(), new ServerAddress("localhost"))).when(collection).listIndexes();
        assertFalse(MongoStore.checkIndex(collection, wanted, "DescriptionVector"));
    }
}
