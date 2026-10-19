package dev.diogo.conversorlocal;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Only in the separate test APK. Exercises SAF with opaque IDs and real URI grants. */
public class TestDocumentsProvider extends DocumentsProvider {
    private static final String[] COLUMNS = {"document_id", "_display_name", "mime_type", "flags", "_size", "last_modified"};
    @Override public boolean onCreate() { return true; }
    private File root() { File root = new File(getContext().getFilesDir(), "documents"); root.mkdirs(); return root; }
    private File file(String id) {
        File root = root();
        if (id.equals("root")) return root;
        if (id.equals("readonly")) { File dir = new File(root, "Somente leitura"); dir.mkdirs(); return dir; }
        File file = new File(root, id.startsWith("item:") ? id.substring(5) : id);
        try { if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) throw new SecurityException(); }
        catch (IOException error) { throw new IllegalStateException(error); }
        return file;
    }
    private String id(File file) { return file.getName().equals("Somente leitura") ? "readonly" : "item:" + file.getName(); }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{"root_id", "document_id", "title", "flags", "mime_types"} : projection;
        MatrixCursor cursor = new MatrixCursor(columns); MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            Object value = null;
            switch (column) {
                case "root_id": value = "test-root"; break;
                case "document_id": value = "root"; break;
                case "title": value = "Testes Conversor"; break;
                case "flags": value = DocumentsContract.Root.FLAG_SUPPORTS_CREATE | DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD; break;
                case "mime_types": value = "*/*"; break;
            }
            row.add(column, value);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection); row(cursor, documentId); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        File[] files = file(parent).listFiles();
        if (files != null) for (File file : files) row(cursor, id(file));
        return cursor;
    }
    private void row(MatrixCursor cursor, String id) {
        File file = file(id); MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            switch (column) {
                case "document_id": value = id; break;
                case "_display_name": value = id.equals("root") ? "Trabalhos com ação" : file.getName(); break;
                case "mime_type":
                    value = file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : file.getName().endsWith(".pdf") ? "application/pdf" : file.getName().endsWith(".docx") ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : file.getName().endsWith(".pptx") ? "application/vnd.openxmlformats-officedocument.presentationml.presentation" : "application/octet-stream"; break;
                case "flags": value = id.equals("readonly") ? 0 : file.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE; break;
                case "_size": value = file.length(); break;
                case "last_modified": value = file.lastModified(); break;
            }
            row.add(column, value);
        }
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException { return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode)); }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!parent.equals("root")) throw new SecurityException("Pasta sem permissão");
        File file = file("item:" + name);
        try { if (!file.createNewFile()) throw new IOException("Não pode sobrescrever"); }
        catch (IOException error) { throw new FileNotFoundException(error.toString()); }
        return id(file);
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException { if (!file(id).delete()) throw new FileNotFoundException("Falha ao remover"); }
    @Override public boolean isChildDocument(String parent, String id) { return parent.equals("root") && !id.equals("root"); }
}
