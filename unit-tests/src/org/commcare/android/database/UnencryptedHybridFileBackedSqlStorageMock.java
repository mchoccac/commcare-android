package org.commcare.android.database;

import org.javarosa.core.services.storage.Persistable;

import java.io.ByteArrayOutputStream;

/**
 * @author Phillip Mates (pmates@dimagi.com).
 */
public class UnencryptedHybridFileBackedSqlStorageMock<T extends Persistable>
        extends UnencryptedHybridFileBackedSqlStorage<T> {
    private static boolean storeInFS = false;

    public UnencryptedHybridFileBackedSqlStorageMock(String table,
                                                     Class<? extends T> ctype,
                                                     AndroidDbHelper helper,
                                                     String baseDir) {
        super(table, ctype, helper, baseDir);
    }

    public static void alwaysPutInDatabase() {
        storeInFS = false;
    }

    public static void alwaysPutInFilesystem() {
        storeInFS = true;
    }

    @Override
    protected boolean blobFitsInDb(ByteArrayOutputStream bos) {
        return !storeInFS;
    }
}
