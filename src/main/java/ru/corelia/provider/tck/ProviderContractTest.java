package ru.corelia.provider.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static ru.corelia.support.Json.object;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.corelia.http.ApiException;
import ru.corelia.provider.model.AttachmentMetadata;
import ru.corelia.provider.model.BinaryStoreRequest;
import ru.corelia.provider.model.DocumentMutation;
import ru.corelia.provider.model.DocumentSearchRequest;
import ru.corelia.provider.model.DocumentSnapshot;
import ru.corelia.provider.model.DocumentVersion;
import ru.corelia.provider.model.TaskSearchRequest;
import ru.corelia.provider.model.WorkflowContext;
import ru.corelia.provider.model.WorkflowTask;

/** Поведенческий контракт provider-neutral SPI без знания реализации adapter-а. */
public abstract class ProviderContractTest {
    protected ProviderFixture fixture;

    protected abstract ProviderFixture fixture();

    @BeforeEach void prepareFixture() {
        fixture = fixture();
        fixture.seed(document(), version(1));
    }

    @Test void readsAndSearchesDocument() {
        assertEquals("document-1", fixture.documents().get("TEST", "document-1", fixture.allowedAuth()).id());
        assertEquals(1, fixture.documents().search(new DocumentSearchRequest("TEST", 0, 10), fixture.allowedAuth()).items().size());
        assertEquals("TEST", fixture.documentTypes().available(fixture.allowedAuth()).getFirst().code());
    }

    @Test void updatesVersionsWithOptimisticLockAndIdempotency() {
        var mutation = new DocumentMutation("document-1", "TEST", 1, "token-1", Map.of("title", object("value", "новое")),
                version(2), null, null, null, "request-1", "hash-1", object("ok", true));
        fixture.versions().commit(mutation, fixture.allowedAuth());
        assertEquals(2, fixture.versions().state("TEST", "document-1", fixture.allowedAuth()).document().currentVersion());
        assertEquals(2, fixture.versions().documentVersions("document-1", fixture.allowedAuth()).size());
        fixture.versions().commit(mutation, fixture.allowedAuth());
        assertNotNull(fixture.versions().receipt("request-1", fixture.allowedAuth()));
        assertThrows(ApiException.class, () -> fixture.versions().commit(
                new DocumentMutation("document-1", "TEST", 1, "token-1", Map.of(), version(3), null, null, null,
                        "request-2", "hash-2", object()), fixture.allowedAuth()));
    }

    @Test void storesAndReadsAttachmentContent() throws Exception {
        var stored = fixture.storage().store(new BinaryStoreRequest("document-1", "attachment-1", "file.txt", "text/plain", 4, "sum"),
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), fixture.allowedAuth());
        assertEquals("data", new String(fixture.storage().read(stored.reference(), fixture.allowedAuth()).readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test void startsWorkflowAndCompletesTask() {
        var process = fixture.workflows().start(new WorkflowContext("document-1", "TEST", Map.of(), "user", "key", null, "create", "hash"), fixture.allowedAuth());
        assertEquals("STARTED", fixture.workflows().process(process.id(), fixture.allowedAuth()).state());
        fixture.seed(task());
        assertEquals(1, fixture.tasks().search(new TaskSearchRequest(java.util.Set.of("NEW")), fixture.allowedAuth()).size());
        fixture.tasks().start("task-1", fixture.allowedAuth());
        fixture.tasks().complete("task-1", Map.of(), fixture.allowedAuth());
        assertEquals("COMPLETED", fixture.tasks().task("task-1", fixture.allowedAuth()).status());
    }

    @Test void enforcesPermissions() {
        fixture.permissions().require("document:edit", fixture.allowedAuth());
        assertThrows(ApiException.class, () -> fixture.permissions().require("document:edit", fixture.deniedAuth()));
    }

    private static DocumentSnapshot document() {
        return new DocumentSnapshot("document-1", "TEST", "DRAFT", 1, Map.of("title", object("value", "исходное")), "user", Instant.EPOCH, "token-1");
    }

    private static DocumentVersion version(int number) {
        return new DocumentVersion("version-" + number, "document-1", number, 1, Map.of("title", object("value", "v" + number)), "DRAFT", Instant.EPOCH, "user", null, List.of());
    }

    private static WorkflowTask task() {
        return new WorkflowTask("task-1", "document-1", "TEST", "NEW", "user", "Пользователь", "editor", "Задача", "", Map.of(), List.of());
    }
}
