package org.commcare.android.database;

import org.commcare.android.CommCareTestRunner;
import org.commcare.android.util.TestAppInstaller;
import org.commcare.android.util.TestUtils;
import org.commcare.dalvik.BuildConfig;
import org.commcare.dalvik.application.CommCareApplication;
import org.commcare.data.xml.DataModelPullParser;
import org.commcare.xml.AndroidTransactionParserFactory;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.reference.ReferenceManager;
import org.javarosa.core.reference.ResourceReferenceFactory;
import org.javarosa.core.services.storage.EntityFilter;
import org.javarosa.core.services.storage.IStorageIterator;
import org.javarosa.core.services.storage.IStorageUtility;
import org.javarosa.core.services.storage.IStorageUtilityIndexed;
import org.javarosa.xml.util.InvalidStructureException;
import org.javarosa.xml.util.UnfullfilledRequirementsException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Vector;

/**
 * Test file-backed sql storage currently used to store fixtures, which can get
 * large.  File-backed storage can store encrypted or unencrypted files.
 *
 * @author Phillip Mates (pmates@dimagi.com).
 */
@Config(application = org.commcare.dalvik.application.CommCareTestApplication.class,
        constants = BuildConfig.class)
@RunWith(CommCareTestRunner.class)
public class SqlFileBackedStorageTests {
    private AndroidSandbox sandbox;

    @Before
    public void setup() {
        UnencryptedFileBackedSqlStorageMock.alwaysPutInFilesystem();
        FileBackedSqlStorageMock.alwaysPutInFilesystem();

        // needed to resolve "jr://resource" type references
        ReferenceManager._().addReferenceFactory(new ResourceReferenceFactory());

        TestUtils.initializeStaticTestStorage();
        TestAppInstaller.setupPrototypeFactory();

        TestAppInstaller appTestInstaller =
                new TestAppInstaller("jr://resource/commcare-apps/archive_form_tests/profile.ccpr",
                        "test", "123");
        appTestInstaller.installAppAndLogin();

        sandbox = new AndroidSandbox(CommCareApplication._());

        try {
            parseIntoSandbox(this.getClass().getClassLoader().getResourceAsStream("ipm_restore.xml"), false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * User level fixtures are encrypted. To do so, they are stored in
     * encrypted files and the key is stored in the encrypted databse. This
     * test ensures that the file is actually encrypted by trying to
     * deserialize the contents of a fixture file w/o decrypting the file
     * first.
     */
    @Test
    public void testStoredEncrypted() {
        IStorageUtilityIndexed<FormInstance> userFixtureStorage = sandbox.getUserFixtureStorage();
        File dbDir = ((FileBackedSqlStorage<FormInstance>)userFixtureStorage).getDbDirForTesting();
        File[] serializedFixtureFiles = dbDir.listFiles();
        Assert.assertTrue(serializedFixtureFiles.length > 0);
        try {
            ((FileBackedSqlStorage<FormInstance>)userFixtureStorage).newObject(new FileInputStream(serializedFixtureFiles[0]), -1);
        } catch (FileNotFoundException e) {
            Assert.fail("Unable to find db storage file that should exist");
        } catch (RuntimeException e) {
            // we expect to fail here because the stream wasn't decrypted
        } catch (Exception e) {
            Assert.fail("Should have failed with a runtime exception when trying to deserialize an encrypted object");
        }
    }

    /**
     * App level fixtures are stored un-encrypted. To do so, they are stored in
     * plain-text files.  This test ensures that by trying to deserialize the
     * contents of one of those files.
     */
    @Test
    public void testStoredUnencrypted() {
        IStorageUtilityIndexed<FormInstance> appFixtureStorage = sandbox.getAppFixtureStorage();
        File dbDir = ((FileBackedSqlStorage<FormInstance>)appFixtureStorage).getDbDirForTesting();
        File[] serializedFixtureFiles = dbDir.listFiles();
        Assert.assertTrue(serializedFixtureFiles.length > 0);
        try {
            ((UnencryptedFileBackedSqlStorage<FormInstance>)appFixtureStorage).newObject(new FileInputStream(serializedFixtureFiles[0]), -1);
        } catch (Exception e) {
            Assert.fail("Should be able to deserialize an unencrypted object");
        }
    }

    @Test
    public void testRemoveAllDeletesFiles() {
        IStorageUtilityIndexed<FormInstance> userFixtureStorage = sandbox.getUserFixtureStorage();
        File dbDir = ((FileBackedSqlStorage<FormInstance>)userFixtureStorage).getDbDirForTesting();

        ArrayList<File> removedFiles = new ArrayList<>();
        for (IStorageIterator i = userFixtureStorage.iterate(); i.hasMore(); ) {
            File fixtureFile =
                    new File(((FileBackedSqlStorage<FormInstance>)userFixtureStorage).getEntryFilenameForTesting(i.nextID()));
            removedFiles.add(fixtureFile);
        }

        userFixtureStorage.removeAll();

        for (File fixtureFile : removedFiles) {
            Assert.assertTrue(!fixtureFile.exists());
        }
        Assert.assertTrue(!dbDir.exists());
    }

    @Test
    public void testRemoveEntityFilterDeleteFiles() {
        IStorageUtilityIndexed<FormInstance> userFixtureStorage = sandbox.getUserFixtureStorage();
        File dbDir = ((FileBackedSqlStorage<FormInstance>)userFixtureStorage).getDbDirForTesting();
        int fileCountBefore = dbDir.listFiles().length;

        userFixtureStorage.removeAll(new EntityFilter<FormInstance>() {
            @Override
            public boolean matches(FormInstance fixture) {
                return "commtrack:products".equals(fixture.getRoot().getInstanceName());
            }
        });

        int fileCountAfter = dbDir.listFiles().length;
        Assert.assertTrue(fileCountBefore - fileCountAfter == 1);

        // make sure we can read all the existing records just fine
        for (IStorageIterator i = userFixtureStorage.iterate(); i.hasMore(); ) {
            i.nextRecord();
        }
    }

    @Test
    public void testRemoveDeletesFiles() {
        IStorageUtilityIndexed<FormInstance> userFixtureStorage = sandbox.getUserFixtureStorage();
        File dbDir = ((FileBackedSqlStorage<FormInstance>)userFixtureStorage).getDbDirForTesting();
        File[] serializedFixtureFiles = dbDir.listFiles();
        Assert.assertTrue(serializedFixtureFiles.length > 0);
        int count = 0;
        int idOne = -1;
        for (IStorageIterator i = userFixtureStorage.iterate(); i.hasMore(); ) {
            if (count == 0) {
                removeOneEntry(i.nextID(), userFixtureStorage);
            } else if (count == 1) {
                idOne = i.nextID();
            } else if (count == 2) {
                removeTwoEntries(idOne, i.nextID(), userFixtureStorage);
            } else {
                // seems to be required; otherwise iterator loops forever. Not
                // sure if it is a robolectric bug or a bug in our iterator
                // that comes up when we iterate and delete at the same time
                break;
            }

            count++;
        }
    }

    private void removeOneEntry(int id, IStorageUtility<FormInstance> userFixtureStorage) {
        String fixtureFilename =
                ((FileBackedSqlStorage<FormInstance>)userFixtureStorage).getEntryFilenameForTesting(id);
        File fixtureFile = new File(fixtureFilename);
        Assert.assertTrue(fixtureFile.exists());
        userFixtureStorage.remove(id);
        Assert.assertTrue(!fixtureFile.exists());
    }

    private void removeTwoEntries(int idOne, int idTwo, IStorageUtility<FormInstance> userFixtureStorage) {
        ArrayList<Integer> toRemoveList = new ArrayList<>();
        toRemoveList.add(idOne);
        toRemoveList.add(idTwo);

        String fixtureOneFilename =
                ((FileBackedSqlStorage<FormInstance>)userFixtureStorage).getEntryFilenameForTesting(idOne);
        String fixtureTwoFilename =
                ((FileBackedSqlStorage<FormInstance>)userFixtureStorage).getEntryFilenameForTesting(idTwo);
        File fixtureFileOne = new File(fixtureOneFilename);
        File fixtureFileTwo = new File(fixtureTwoFilename);

        Assert.assertTrue(fixtureFileOne.exists());
        Assert.assertTrue(fixtureFileTwo.exists());

        ((FileBackedSqlStorage<FormInstance>)userFixtureStorage).remove(toRemoveList);

        Assert.assertTrue(!fixtureFileOne.exists());
        Assert.assertTrue(!fixtureFileTwo.exists());
    }

    @Test
    public void testUpdate() {
        // test encrypted update
        FileBackedSqlStorage<FormInstance> userFixtureStorage =
                CommCareApplication._().getFileBackedUserStorage("fixture", FormInstance.class);
        FormInstance form = userFixtureStorage.getRecordForValues(new String[]{FormInstance.META_ID}, new String[]{"commtrack:programs"});

        String newName = "new_name";
        form.setName(newName);
        userFixtureStorage.update(form.getID(), form);

        form = userFixtureStorage.getRecordForValues(new String[]{FormInstance.META_ID}, new String[]{"commtrack:programs"});
        Assert.assertEquals(newName, form.getName());

        // test unencrypted update
        UnencryptedFileBackedSqlStorage<FormInstance> appFixtureStorage =
                CommCareApplication._().getCurrentApp().getFileBackedStorage("fixture", FormInstance.class);
        form = appFixtureStorage.getRecordForValues(new String[]{FormInstance.META_ID}, new String[]{"user-groups"});

        form.setName(newName);
        appFixtureStorage.update(form.getID(), form);

        form = appFixtureStorage.getRecordForValues(new String[]{FormInstance.META_ID}, new String[]{"user-groups"});
        Assert.assertEquals(newName, form.getName());
    }

    @Test
    public void testRecordLookup() {
        // test encrypted record lookup
        FileBackedSqlStorage<FormInstance> userFixtureStorage =
                CommCareApplication._().getFileBackedUserStorage("fixture", FormInstance.class);

        Vector<FormInstance> forms = userFixtureStorage.getRecordsForValues(new String[]{FormInstance.META_ID}, new String[]{"commtrack:programs"});
        Assert.assertTrue(forms.size() == 1);

        FormInstance form = userFixtureStorage.getRecordForValues(new String[]{FormInstance.META_ID}, new String[]{"commtrack:programs"});
        Assert.assertEquals(forms.firstElement().getRoot(), form.getRoot());

        form = userFixtureStorage.getRecordForValue(FormInstance.META_ID, "commtrack:programs");
        Assert.assertEquals(forms.firstElement().getRoot(), form.getRoot());

        // Test unencrpyted record lookup
        UnencryptedFileBackedSqlStorage<FormInstance> appFixtureStorage =
                CommCareApplication._().getCurrentApp().getFileBackedStorage("fixture", FormInstance.class);

        forms = appFixtureStorage.getRecordsForValues(new String[]{FormInstance.META_ID}, new String[]{"user-groups"});
        Assert.assertTrue(forms.size() == 1);

        form = appFixtureStorage.getRecordForValues(new String[]{FormInstance.META_ID}, new String[]{"user-groups"});
        Assert.assertEquals(forms.firstElement().getRoot(), form.getRoot());

        form = appFixtureStorage.getRecordForValue(FormInstance.META_ID, "user-groups");
        Assert.assertEquals(forms.firstElement().getRoot(), form.getRoot());
    }

    private static void parseIntoSandbox(InputStream stream, boolean failfast)
            throws InvalidStructureException, IOException, UnfullfilledRequirementsException, XmlPullParserException {
        AndroidTransactionParserFactory factory = new AndroidTransactionParserFactory(CommCareApplication._().getApplicationContext(), null);
        DataModelPullParser parser = new DataModelPullParser(stream, factory, failfast, true);
        parser.parse();
    }
}
