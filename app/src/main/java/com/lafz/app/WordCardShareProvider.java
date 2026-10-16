package com.lafz.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

public class WordCardShareProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || uri.getPathSegments().size() != 2 || !"card".equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException("Unknown Lafz card.");
        }
        String id = uri.getPathSegments().get(1);
        Word word = LafzRepository.get(getContext()).byId(id);
        if (word == null) throw new FileNotFoundException("Unknown Lafz word.");
        File file = new File(new File(getContext().getCacheDir(), "shared-words"), id + ".png");
        if (!file.isFile()) throw new FileNotFoundException("Lafz card is missing.");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
