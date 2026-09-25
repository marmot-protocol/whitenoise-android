package dev.ipf.fixture;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/** Disposable, read-only SAF provider for the PR #2830 device matrix. */
public final class FixtureDocumentsProvider extends DocumentsProvider {
    private static final String ROOT = "fixtures";
    private static final String[] ROOT_COLUMNS = {
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_SUMMARY,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_MIME_TYPES
    };
    private static final String[] DOC_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_FLAGS
    };
    private static final String[][] FILES = {
        {"text", "note.txt", "text/plain", "note.txt"},
        {"pdf", "paper.pdf", "application/pdf", "paper.pdf"},
        {"zip", "bundle.zip", "application/zip", "bundle.zip"},
        {"office", "report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "report.docx"},
        {"audio", "tone.wav", "audio/wav", "tone.wav"},
        {"video", "clip.mp4", "video/mp4", "clip.mp4"},
        {"binary", "opaque.bin", "application/octet-stream", "opaque.bin"},
        {"mismatch", "looks-like-pdf.pdf", "application/octet-stream", "opaque.bin"},
        {"unhandled", "no-viewer.wnmatrix", "application/vnd.wnmatrix.no-viewer", "opaque.bin"},
        {"empty", "empty.txt", "text/plain", "empty.txt"},
        {"unreadable", "unreadable.txt", "text/plain", "note.txt"}
    };

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor out = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        MatrixCursor.RowBuilder row = out.newRow();
        for (String key : out.getColumnNames()) {
            switch (key) {
                case DocumentsContract.Root.COLUMN_ROOT_ID: row.add(ROOT); break;
                case DocumentsContract.Root.COLUMN_DOCUMENT_ID: row.add(ROOT); break;
                case DocumentsContract.Root.COLUMN_TITLE: row.add("WN matrix files"); break;
                case DocumentsContract.Root.COLUMN_SUMMARY: row.add("Disposable PR test provider"); break;
                case DocumentsContract.Root.COLUMN_FLAGS: row.add(0); break;
                case DocumentsContract.Root.COLUMN_MIME_TYPES: row.add("*/*"); break;
                default: row.add(null);
            }
        }
        return out;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor out = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        if (ROOT.equals(documentId)) {
            addDocument(out, ROOT, "WN matrix files", DocumentsContract.Document.MIME_TYPE_DIR, 0);
            return out;
        }
        String[] file = find(documentId);
        addDocument(out, file[0], file[1], file[2], size(file));
        return out;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        if (!ROOT.equals(parentDocumentId)) throw new FileNotFoundException(parentDocumentId);
        MatrixCursor out = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        for (String[] file : FILES) addDocument(out, file[0], file[1], file[2], size(file));
        return out;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        if ("unreadable".equals(documentId)) throw new FileNotFoundException("fixture revoked");
        String[] file = find(documentId);
        File target = new File(getContext().getCacheDir(), "fixture-" + file[0]);
        try (InputStream input = getContext().getAssets().open(file[3]);
             OutputStream output = Files.newOutputStream(target.toPath())) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        } catch (IOException error) {
            throw new FileNotFoundException(error.toString());
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static String[] find(String id) throws FileNotFoundException {
        for (String[] file : FILES) if (file[0].equals(id)) return file;
        throw new FileNotFoundException(id);
    }

    private long size(String[] file) {
        if ("empty".equals(file[0])) return 0;
        try {
            return getContext().getAssets().openFd(file[3]).getLength();
        } catch (IOException ignored) {
            try (InputStream input = getContext().getAssets().open(file[3])) {
                long count = 0;
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) count += n;
                return count;
            } catch (IOException error) {
                return -1;
            }
        }
    }

    private static void addDocument(MatrixCursor out, String id, String name, String mime, long size) {
        MatrixCursor.RowBuilder row = out.newRow();
        for (String key : out.getColumnNames()) {
            switch (key) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row.add(name); break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE: row.add(mime); break;
                case DocumentsContract.Document.COLUMN_SIZE: row.add(size); break;
                case DocumentsContract.Document.COLUMN_FLAGS: row.add(0); break;
                default: row.add(null);
            }
        }
    }
}
