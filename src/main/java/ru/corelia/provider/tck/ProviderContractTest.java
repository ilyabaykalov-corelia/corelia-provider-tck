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
        fixture.seed(document(fixture.data()), version(fixture.data(), 1));
    }

    @Test void readsAndSearchesDocument() {
        var data = fixture.data();
        assertEquals(data.documentId(), fixture.documents().get(data.documentType(), data.documentId(), fixture.allowedAuth()).id());
        assertEquals(1, fixture.documents().search(new DocumentSearchRequest(data.documentType(), 0, 10), fixture.allowedAuth()).items().size());
        assertEquals(data.documentType(), fixture.documentTypes().available(fixture.allowedAuth()).getFirst().code());
    }

    @Test void updatesVersionsWithOptimisticLockAndIdempotency() {
        var data = fixture.data();
        var mutation = new DocumentMutation(data.documentId(), data.documentType(), 1, data.changeToken(), Map.of("title", object("value", "новое")),
                version(data, 2), null, null, null, "request-1", "hash-1", object("ok", true));
        fixture.versions().commit(mutation, fixture.allowedAuth());
        assertEquals(2, fixture.versions().state(data.documentType(), data.documentId(), fixture.allowedAuth()).document().currentVersion());
        assertEquals(2, fixture.versions().documentVersions(data.documentId(), fixture.allowedAuth()).size());
        fixture.versions().commit(mutation, fixture.allowedAuth());
        assertNotNull(fixture.versions().receipt("request-1", fixture.allowedAuth()));
        assertThrows(ApiException.class, () -> fixture.versions().commit(
                new DocumentMutation(data.documentId(), data.documentType(), 1, data.changeToken(), Map.of(), version(data, 3), null, null, null,
                        "request-2", "hash-2", object()), fixture.allowedAuth()));
    }

    @Test void storesAndReadsAttachmentContent() throws Exception {
        var stored = fixture.storage().store(new BinaryStoreRequest(fixture.data().documentId(), "attachment-1", "file.txt", "text/plain", 4, "sum"),
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), fixture.allowedAuth());
        assertEquals("data", new String(fixture.storage().read(stored.reference(), fixture.allowedAuth()).readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test void startsWorkflowAndCompletesTask() {
        var data = fixture.data();
        var process = fixture.workflows().start(new WorkflowContext(data.documentId(), data.documentType(), Map.of(), "user", "key", null, "create", "hash"), fixture.allowedAuth());
        assertEquals("STARTED", fixture.workflows().process(process.id(), fixture.allowedAuth()).state());
        fixture.seed(task(data));
        assertEquals(1, fixture.tasks().search(new TaskSearchRequest(java.util.Set.of("NEW")), fixture.allowedAuth()).size());
        fixture.tasks().start(data.taskId(), fixture.allowedAuth());
        fixture.tasks().complete(data.taskId(), Map.of(), fixture.allowedAuth());
        assertEquals("COMPLETED", fixture.tasks().task(data.taskId(), fixture.allowedAuth()).status());
    }

    @Test void enforcesPermissions() {
        fixture.permissions().require("document:edit", fixture.allowedAuth());
        assertThrows(ApiException.class, () -> fixture.permissions().require("document:edit", fixture.deniedAuth()));
    }

    private static DocumentSnapshot document(ProviderFixture.Data data) {
        return new DocumentSnapshot(data.documentId(), data.documentType(), "DRAFT", 1, Map.of("title", object("value", "исходное")), "user", Instant.EPOCH, data.changeToken());
    }

    private static DocumentVersion version(ProviderFixture.Data data, int number) {
        return new DocumentVersion("version-" + number, data.documentId(), number, 1, Map.of("title", object("value", "v" + number)), "DRAFT", Instant.EPOCH, "user", null, List.of());
    }

    private static WorkflowTask task(ProviderFixture.Data data) {
        return new WorkflowTask(data.taskId(), data.documentId(), data.documentType(), "NEW", "user", "Пользователь", "editor", "Задача", "", Map.of(), List.of());
    }
}
