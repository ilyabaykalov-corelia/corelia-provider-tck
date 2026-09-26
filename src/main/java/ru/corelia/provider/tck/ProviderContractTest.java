package ru.corelia.provider.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test void initializesDocumentVersionLifecycle() {
        fixture = fixture();
        var data = fixture.data();
        fixture.seed(document(data, 0), version(data, 0));
        fixture.versions().commit(new DocumentMutation(data.documentId(), data.documentType(), 0, data.changeToken(),
                Map.of(data.attribute(), object("value", "исходное")), version(data, 1), null, null, null,
                "document-initialize", "hash-initialize", object("version", 1)), fixture.allowedAuth());
        assertEquals(1, fixture.versions().state(data.documentType(), data.documentId(), fixture.allowedAuth()).document().currentVersion());
    }

    @Test void updatesVersionsWithOptimisticLockAndIdempotency() {
        var data = fixture.data();
        var mutation = new DocumentMutation(data.documentId(), data.documentType(), 1, data.changeToken(), Map.of(data.attribute(), object("value", "новое")),
                version(data, 2), version(data, 1), null, null, "request-1", "hash-1", object("ok", true));
        fixture.versions().commit(mutation, fixture.allowedAuth());
        assertEquals(2, fixture.versions().state(data.documentType(), data.documentId(), fixture.allowedAuth()).document().currentVersion());
        assertEquals(2, fixture.versions().documentVersions(data.documentId(), fixture.allowedAuth()).size());
        fixture.versions().commit(mutation, fixture.allowedAuth());
        assertNotNull(fixture.versions().receipt("request-1", fixture.allowedAuth()));
        assertThrows(ApiException.class, () -> fixture.versions().commit(
                new DocumentMutation(data.documentId(), data.documentType(), 1, data.changeToken(), Map.of(), version(data, 3), version(data, 2), null, null,
                        "request-2", "hash-2", object()), fixture.allowedAuth()));
    }

    @Test void storesAndReadsAttachmentContent() throws Exception {
        var stored = fixture.storage().store(new BinaryStoreRequest(fixture.data().documentId(), "attachment-1", "file.txt", "text/plain", 4, "sum"),
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), fixture.allowedAuth());
        assertEquals("data", new String(fixture.storage().read(stored.reference(), fixture.allowedAuth()).readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test void uploadsReplacesDeletesAttachmentAndKeepsHistory() {
        var data = fixture.data();
        var initial = attachment(data, "attachment-1", "attachment-1", 1);
        fixture.versions().commit(attachmentMutation(data, initial, null, version(data, 1, List.of(initial)), "attachment-upload"), fixture.allowedAuth());
        assertEquals(initial.id(), fixture.attachments().find(initial.id(), fixture.allowedAuth()).id());

        var state = fixture.versions().state(data.documentType(), data.documentId(), fixture.allowedAuth());
        var replacement = attachment(data, "attachment-2", initial.logicalId(), 2);
        fixture.versions().commit(new DocumentMutation(data.documentId(), data.documentType(), state.document().currentVersion(), state.document().changeToken(),
                Map.of(), version(data, 3, List.of(replacement)), state.currentVersion(), replacement, initial, "attachment-replace", "hash-replace", object("changeToken", "token-3")), fixture.allowedAuth());
        assertEquals(2, fixture.attachments().attachmentVersions(replacement.id(), fixture.allowedAuth()).size());

        state = fixture.versions().state(data.documentType(), data.documentId(), fixture.allowedAuth());
        fixture.versions().commit(new DocumentMutation(data.documentId(), data.documentType(), state.document().currentVersion(), state.document().changeToken(),
                Map.of(), version(data, 4, List.of()), state.currentVersion(), null, replacement, "attachment-delete", "hash-delete", object("changeToken", "token-4")), fixture.allowedAuth());
        assertFalse(fixture.versions().attachments(data.documentId(), fixture.allowedAuth()).stream().anyMatch(AttachmentMetadata::current));
    }

    @Test void startsWorkflowAndCompletesTask() {
        var data = fixture.data();
        var process = fixture.workflows().start(new WorkflowContext(data.documentId(), data.documentType(), Map.of(), "user", "key", null, "create", "hash"), fixture.allowedAuth());
        assertNotNull(process.id());
        fixture.seed(task(data));
        assertEquals(1, fixture.tasks().search(new TaskSearchRequest(java.util.Set.of("NEW")), fixture.allowedAuth()).size());
        fixture.tasks().start(data.taskId(), fixture.allowedAuth());
        fixture.tasks().complete(data.taskId(), Map.of(), fixture.allowedAuth());
        assertEquals(0, fixture.tasks().search(new TaskSearchRequest(java.util.Set.of("NEW")), fixture.allowedAuth()).size());
    }

    @Test void enforcesPermissions() {
        fixture.permissions().require(fixture.data().permission(), fixture.allowedAuth());
        assertThrows(ApiException.class, () -> fixture.permissions().require(fixture.data().permission(), fixture.deniedAuth()));
    }

    private static DocumentSnapshot document(ProviderFixture.Data data) {
        return document(data, 1);
    }

    private static DocumentSnapshot document(ProviderFixture.Data data, int version) {
        return new DocumentSnapshot(data.documentId(), data.documentType(), "DRAFT", version, Map.of(data.attribute(), object("value", "исходное")), "user", Instant.EPOCH, data.changeToken());
    }

    private static DocumentVersion version(ProviderFixture.Data data, int number) {
        return new DocumentVersion("version-" + number, data.documentId(), number, 1, Map.of(data.attribute(), object("value", "v" + number)), "DRAFT", Instant.EPOCH, "user", null, List.of());
    }

    private static DocumentVersion version(ProviderFixture.Data data, int number, List<AttachmentMetadata> attachments) {
        return new DocumentVersion("version-" + number, data.documentId(), number, 1, Map.of(data.attribute(), object("value", "v" + number)), "DRAFT", Instant.EPOCH, "user", null, attachments);
    }

    private static AttachmentMetadata attachment(ProviderFixture.Data data, String id, String logicalId, long number) {
        return new AttachmentMetadata(id, logicalId, data.documentId(), "file.txt", "text/plain", 4, number, true, Instant.EPOCH,
                new ru.corelia.provider.model.StorageReference("provider://" + id));
    }

    private static DocumentMutation attachmentMutation(ProviderFixture.Data data, AttachmentMetadata created, AttachmentMetadata retired,
                                                        DocumentVersion closed, String key) {
        int nextNumber = closed.number() + 1;
        return new DocumentMutation(data.documentId(), data.documentType(), closed.number(), data.changeToken(), Map.of(),
                version(data, nextNumber, created == null ? List.of() : List.of(created)), closed, created, retired,
                key, "hash-" + key, object("changeToken", "token-" + nextNumber));
    }

    private static WorkflowTask task(ProviderFixture.Data data) {
        return new WorkflowTask(data.taskId(), data.documentId(), data.documentType(), "NEW", "user", "Пользователь", "editor", "Задача", "", Map.of(), List.of());
    }
}
