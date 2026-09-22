package ru.corelia.provider.tck;

import ru.corelia.auth.AuthContext;
import ru.corelia.provider.AttachmentCatalog;
import ru.corelia.provider.BinaryStorage;
import ru.corelia.provider.DocumentStore;
import ru.corelia.provider.DocumentTypeProvider;
import ru.corelia.provider.DocumentVersionStore;
import ru.corelia.provider.PermissionProvider;
import ru.corelia.provider.TaskProvider;
import ru.corelia.provider.WorkflowProvider;
import ru.corelia.provider.model.DocumentSnapshot;
import ru.corelia.provider.model.DocumentVersion;
import ru.corelia.provider.model.WorkflowTask;

/** Подготовка конкретного provider для повторно используемых contract tests. */
public interface ProviderFixture {
    record Data(String documentType, String documentId, String changeToken, String taskId, String attribute) {}
    DocumentStore documents();
    DocumentVersionStore versions();
    DocumentTypeProvider documentTypes();
    BinaryStorage storage();
    AttachmentCatalog attachments();
    WorkflowProvider workflows();
    TaskProvider tasks();
    PermissionProvider permissions();
    AuthContext allowedAuth();
    AuthContext deniedAuth();
    default Data data() { return new Data("TEST", "document-1", "token-1", "task-1", "title"); }
    void seed(DocumentSnapshot document, DocumentVersion version);
    void seed(WorkflowTask task);
}
