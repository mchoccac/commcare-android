package org.commcare.activities;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Pair;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.commcare.CommCareApplication;
import org.commcare.adapters.IncompleteFormListAdapter;
import org.commcare.dalvik.R;
import org.commcare.logging.AndroidLogger;
import org.commcare.google.services.analytics.GoogleAnalyticsFields;
import org.commcare.google.services.analytics.GoogleAnalyticsUtils;
import org.commcare.logic.ArchivedFormRemoteRestore;
import org.commcare.models.FormRecordProcessor;
import org.commcare.android.database.user.models.FormRecord;
import org.commcare.android.database.user.models.SessionStateDescriptor;
import org.commcare.preferences.CommCareServerPreferences;
import org.commcare.tasks.DataPullTask;
import org.commcare.tasks.FormRecordCleanupTask;
import org.commcare.tasks.FormRecordLoadListener;
import org.commcare.tasks.FormRecordLoaderTask;
import org.commcare.tasks.PurgeStaleArchivedFormsTask;
import org.commcare.tasks.TaskListener;
import org.commcare.tasks.TaskListenerRegistrationException;
import org.commcare.utils.AndroidCommCarePlatform;
import org.commcare.utils.CommCareUtil;
import org.commcare.utils.SessionUnavailableException;
import org.commcare.views.IncompleteFormRecordView;
import org.commcare.views.dialogs.StandardAlertDialog;
import org.commcare.views.dialogs.CustomProgressDialog;
import org.javarosa.core.services.Logger;
import org.javarosa.core.services.locale.Localization;


public class FormRecordListActivity extends SessionAwareCommCareActivity<FormRecordListActivity>
        implements TextWatcher, FormRecordLoadListener, OnItemClickListener, TaskListener<Void, Void> {
    private static final String TAG = FormRecordListActivity.class.getSimpleName();

    private static final String FORM_RECORD_URL = CommCareServerPreferences.PREFS_FORM_RECORD_KEY;

    private static final int OPEN_RECORD = Menu.FIRST;
    private static final int DELETE_RECORD = Menu.FIRST + 1;
    private static final int RESTORE_RECORD = Menu.FIRST + 2;
    private static final int SCAN_RECORD = Menu.FIRST + 3;

    private static final int DOWNLOAD_FORMS = Menu.FIRST;
    private static final int MENU_SUBMIT_QUARANTINE_REPORT = Menu.FIRST + 1;

    private static final int BARCODE_FETCH = 1;

    public static final String KEY_INITIAL_RECORD_ID = "cc_initial_rec_id";

    private AndroidCommCarePlatform platform;
    private IncompleteFormListAdapter adapter;
    private PurgeStaleArchivedFormsTask purgeTask;

    private int initialSelection = -1;

    private EditText searchbox;
    private ListView listView;
    private SearchView searchView;
    private MenuItem searchItem;

    private View.OnClickListener barcodeScanOnClickListener;

    private boolean incompleteMode;

    public enum FormRecordFilter {

        /**
         * Processed and Pending
         **/
        SubmittedAndPending("form.record.filter.subandpending", new String[]{FormRecord.STATUS_SAVED, FormRecord.STATUS_UNSENT}),

        /**
         * Submitted Only
         **/
        Submitted("form.record.filter.submitted", new String[]{FormRecord.STATUS_SAVED}),

        /**
         * Pending Submission
         **/
        Pending("form.record.filter.pending", new String[]{FormRecord.STATUS_UNSENT}),

        /**
         * Incomplete forms
         **/
        Incomplete("form.record.filter.incomplete", new String[]{FormRecord.STATUS_INCOMPLETE}),

        /**
         * Limbo forms
         **/
        Limbo("form.record.filter.limbo", new String[]{FormRecord.STATUS_LIMBO});

        FormRecordFilter(String message, String[] statuses) {
            this.message = message;
            this.statuses = statuses;
        }

        private final String message;
        private final String[] statuses;

        public String getMessage() {
            return message;
        }

        public String[] getStatus() {
            return statuses;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        platform = CommCareApplication.instance().getCommCarePlatform();
        setContentView(R.layout.entity_select_layout);
        findViewById(R.id.entity_select_loading).setVisibility(View.GONE);

        searchbox = (EditText)findViewById(R.id.searchbox);
        LinearLayout header = (LinearLayout)findViewById(R.id.entity_select_header);
        ImageButton barcodeButton = (ImageButton)findViewById(R.id.barcodeButton);

        Spinner filterSelect = (Spinner)findViewById(R.id.entity_select_filter_dropdown);

        listView = (ListView)findViewById(R.id.screen_entity_select_list);
        listView.setOnItemClickListener(this);

        header.setVisibility(View.GONE);
        barcodeButton.setVisibility(View.GONE);

        barcodeScanOnClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callBarcodeScanIntent(FormRecordListActivity.this);
            }
        };

        TextView searchLabel = (TextView)findViewById(R.id.screen_entity_select_search_label);
        searchLabel.setText(this.localize("select.search.label"));

        searchbox.addTextChangedListener(this);
        FormRecordLoaderTask task = new FormRecordLoaderTask(this, CommCareApplication.instance().getUserStorage(SessionStateDescriptor.class), platform);
        task.addListener(this);

        adapter = new IncompleteFormListAdapter(this, platform, task);

        initialSelection = this.getIntent().getIntExtra(KEY_INITIAL_RECORD_ID, -1);

        if (this.getIntent().hasExtra(FormRecord.META_STATUS)) {
            String incomingFilter = this.getIntent().getStringExtra(FormRecord.META_STATUS);
            if (incomingFilter.equals(FormRecord.STATUS_INCOMPLETE)) {
                incompleteMode = true;
                //special case, no special filtering options
                adapter.setFormFilter(FormRecordFilter.Incomplete);
                adapter.resetRecords();
            }
        } else {
            FormRecordFilter[] filters = FormRecordFilter.values();
            String[] names = new String[filters.length];
            for (int i = 0; i < filters.length; ++i) {
                names[i] = Localization.get(filters[i].getMessage());
            }
            ArrayAdapter<String> spinneritems = new ArrayAdapter<>(this, R.layout.form_filter_display, names);
            filterSelect.setAdapter(spinneritems);
            spinneritems.setDropDownViewResource(R.layout.form_filter_item);
            filterSelect.setOnItemSelectedListener(new OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> arg0, View arg1, int index, long id) {
                    // NOTE: This gets called every time a spinner gets
                    // set-up and also every time spinner state is restored
                    // on scree-rotation. Hence we defer onCreate record
                    // loading until this gets triggered automatically.
                    adapter.setFilterAndResetRecords(FormRecordFilter.values()[index]);

                    //This is only relevant with the new menu format, old menus have a hard
                    //button and don't need their menu to be rebuilt
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                        invalidateOptionsMenu();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> arg0) {
                    // TODO Auto-generated method stub

                }
            });
            filterSelect.setVisibility(View.VISIBLE);
        }

        this.registerForContextMenu(listView);
        refreshView();

        restoreLastQueryString();

        if (!isUsingActionBar()) {
            setSearchText(lastQueryString);
        }
    }

    private static void callBarcodeScanIntent(Activity act) {
        Intent i = new Intent("com.google.zxing.client.android.SCAN");
        try {
            act.startActivityForResult(i, BARCODE_FETCH);
        } catch (ActivityNotFoundException anfe) {
            Toast.makeText(act,
                    "No barcode reader available! You can install one " +
                            "from the android market.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // stop showing blocking dialog and getting updates from purge task.
        unregisterTask();
    }

    private void unregisterTask() {
        if (purgeTask != null) {
            try {
                purgeTask.unregisterTaskListener(this);
                dismissProgressDialog();
            } catch (TaskListenerRegistrationException e) {
                Log.e(TAG, "Attempting to unregister a not previously " +
                        "registered TaskListener.");
            }
            purgeTask = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        saveLastQueryString();
    }

    private void onBarcodeFetch(int resultCode, Intent intent) {
        if (resultCode == Activity.RESULT_OK) {
            String result = intent.getStringExtra("SCAN_RESULT");
            if (result != null) {
                result = result.trim();
            }
            setSearchText(result);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        switch (requestCode) {
            case BARCODE_FETCH:
                onBarcodeFetch(resultCode, intent);
                break;
            default:
                super.onActivityResult(requestCode, resultCode, intent);
        }
    }

    @Override
    public String getActivityTitle() {
        if (adapter == null) {
            return Localization.get("app.workflow.saved.heading");
        }

        if (adapter.getFilter() == FormRecordFilter.Incomplete) {
            return Localization.get("app.workflow.incomplete.heading");
        } else {
            return Localization.get("app.workflow.saved.heading");
        }
    }

    /**
     * Get form list from database and insert into view.
     */
    public void refreshView() {
        disableSearch();
        listView.setAdapter(adapter);
    }

    @Override
    protected void onResumeSessionSafe() {
        attachToPurgeTask();

        if (adapter != null && initialSelection != -1) {
            listView.setSelection(adapter.findRecordPosition(initialSelection));
        }
    }

    /**
     * Attach activity to running purge task to block user while form purging
     * is in progress.
     */
    private void attachToPurgeTask() {
        purgeTask = PurgeStaleArchivedFormsTask.getRunningInstance();

        try {
            if (purgeTask != null) {
                purgeTask.registerTaskListener(this);
                showProgressDialog(PurgeStaleArchivedFormsTask.PURGE_STALE_ARCHIVED_FORMS_TASK_ID);
            }
        } catch (TaskListenerRegistrationException e) {
            Log.e(TAG, "Attempting to register a TaskListener to an already " +
                    "registered task.");
        }
    }

    private void setSearchEnabled(boolean enabled) {
        if (isUsingActionBar()) {
            searchView.setEnabled(enabled);
        } else {
            searchbox.setEnabled(enabled);
        }
    }

    private void disableSearch() {
        setSearchEnabled(false);
    }


    private void enableSearch() {
        setSearchEnabled(true);
    }

    /**
     * Stores the path of selected form and finishes.
     */
    @Override
    public void onItemClick(AdapterView<?> listView, View view, int position, long id) {
        if (incompleteMode) {
            GoogleAnalyticsUtils.reportOpenArchivedForm(GoogleAnalyticsFields.LABEL_INCOMPLETE);
        } else {
            GoogleAnalyticsUtils.reportOpenArchivedForm(GoogleAnalyticsFields.LABEL_COMPLETE);
        }
        returnItem(position);
    }

    private void returnItem(int position) {
        if (adapter.isValid(position)) {
            FormRecord value = (FormRecord)adapter.getItem(position);

            // We want to actually launch an interactive form entry.
            Intent i = new Intent();
            i.putExtra("FORMRECORDS", value.getID());
            setResult(RESULT_OK, i);

            finish();
        } else {
            showAlertDialog(StandardAlertDialog.getBasicAlertDialog(this, "Form Missing",
                    Localization.get("form.record.gone.message"), null));
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo)menuInfo;
        IncompleteFormRecordView ifrv = (IncompleteFormRecordView)adapter.getView(info.position, null, null);
        menu.setHeaderTitle(ifrv.mPrimaryTextView.getText() + " (" + ifrv.mRightTextView.getText() + ")");

        FormRecord value = (FormRecord)adapter.getItem(info.position);

        menu.add(Menu.NONE, OPEN_RECORD, OPEN_RECORD, Localization.get("app.workflow.forms.open"));
        menu.add(Menu.NONE, DELETE_RECORD, DELETE_RECORD, Localization.get("app.workflow.forms.delete"));

        if (FormRecord.STATUS_LIMBO.equals(value.getStatus())) {
            menu.add(Menu.NONE, RESTORE_RECORD, RESTORE_RECORD, Localization.get("app.workflow.forms.restore"));
        }

        menu.add(Menu.NONE, SCAN_RECORD, SCAN_RECORD, Localization.get("app.workflow.forms.scan"));
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        try {
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo)item.getMenuInfo();
            switch (item.getItemId()) {
                case OPEN_RECORD:
                    returnItem(info.position);
                    return true;
                case DELETE_RECORD:
                    FormRecordCleanupTask.wipeRecord(this, CommCareApplication.instance().getUserStorage(FormRecord.class).read((int)info.id));
                    listView.post(new Runnable() {
                        @Override
                        public void run() {
                            adapter.notifyDataSetInvalidated();
                        }
                    });
                    return true;
                case RESTORE_RECORD:
                    FormRecord record = (FormRecord)adapter.getItem(info.position);
                    new FormRecordProcessor(this).updateRecordStatus(record, FormRecord.STATUS_UNSENT);
                    adapter.resetRecords();
                    adapter.notifyDataSetChanged();
                    return true;
                case SCAN_RECORD:
                    FormRecord theRecord = (FormRecord)adapter.getItem(info.position);
                    Pair<Boolean, String> result = new FormRecordProcessor(this).verifyFormRecordIntegrity(theRecord);
                    createFormRecordScanResultDialog(result);
            }
            return true;
        } catch (SessionUnavailableException e) {
            //TODO: Login and try again
            return true;
        }
    }

    private void createFormRecordScanResultDialog(Pair<Boolean, String> result) {
        String title;
        if (result.first) {
            title = Localization.get("app.workflow.forms.scan.title.valid");
        } else {
            title = Localization.get("app.workflow.forms.scan.title.invalid");
        }
        int resId = result.first ? R.drawable.checkmark : R.drawable.redx;
        showAlertDialog(StandardAlertDialog.getBasicAlertDialogWithIcon(this, title,
                result.second, resId, null));
    }

    /**
     * Checks if the action bar view is active
     */
    private boolean isUsingActionBar() {
        return searchView != null;
    }

    @SuppressWarnings("NewApi")
    private void setSearchText(CharSequence text) {
        if (isUsingActionBar()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                searchItem.expandActionView();
            }
            searchView.setQuery(text, false);
        }
        searchbox.setText(text);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean parent = super.onCreateOptionsMenu(menu);
        tryToAddSearchActionToAppBar(this, menu, new ActionBarInstantiator() {
            // this should be unnecessary...
            @TargetApi(Build.VERSION_CODES.HONEYCOMB)
            @Override
            public void onActionBarFound(MenuItem searchItem, SearchView searchView, MenuItem barcodeItem) {
                FormRecordListActivity.this.searchItem = searchItem;
                FormRecordListActivity.this.searchView = searchView;
                if (lastQueryString != null && lastQueryString.length() > 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                        searchItem.expandActionView();
                    }
                    setSearchText(lastQueryString);
                    if (adapter != null) {
                        adapter.applyTextFilter(lastQueryString == null ? "" : lastQueryString);
                    }
                }
                FormRecordListActivity.this.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override
                    public boolean onQueryTextSubmit(String query) {
                        return true;
                    }

                    @Override
                    public boolean onQueryTextChange(String newText) {
                        adapter.applyTextFilter(newText);
                        return false;
                    }
                });
            }
        });
        if (!FormRecordFilter.Incomplete.equals(adapter.getFilter())) {
            SharedPreferences prefs = CommCareApplication.instance().getCurrentApp().getAppPreferences();
            String source = prefs.getString(FORM_RECORD_URL, this.getString(R.string.form_record_url));

            //If there's nowhere to fetch forms from, we can't really go fetch them
            if (!source.equals("")) {
                menu.add(0, DOWNLOAD_FORMS, 0, Localization.get("app.workflow.forms.fetch")).setIcon(android.R.drawable.ic_menu_rotate);
            }
            menu.add(0, MENU_SUBMIT_QUARANTINE_REPORT, MENU_SUBMIT_QUARANTINE_REPORT, Localization.get("app.workflow.forms.quarantine.report"));
            return true;
        }
        return parent;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        MenuItem quarantine = menu.findItem(MENU_SUBMIT_QUARANTINE_REPORT);
        if (quarantine != null) {
            if (FormRecordFilter.Limbo.equals(adapter.getFilter())) {
                quarantine.setVisible(true);
            } else {
                quarantine.setVisible(false);
            }
        }
        return menu.hasVisibleItems();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case DOWNLOAD_FORMS:
                SharedPreferences prefs = CommCareApplication.instance().getCurrentApp().getAppPreferences();
                String source = prefs.getString(FORM_RECORD_URL, this.getString(R.string.form_record_url));
                ArchivedFormRemoteRestore.pullArchivedFormsFromServer(source, this, platform);
                return true;
            case MENU_SUBMIT_QUARANTINE_REPORT:
                generateQuarantineReport();
                return true;
            case R.id.barcode_scan_action_bar:
                barcodeScanOnClickListener.onClick(null);
                return true;
            case R.id.menu_settings:
                HomeScreenBaseActivity.createPreferencesMenu(this);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }


    private void generateQuarantineReport() {
        FormRecordProcessor processor = new FormRecordProcessor(this);
        Logger.log(AndroidLogger.TYPE_ERROR_STORAGE, "Beginning form Quarantine report");
        for (int i = 0; i < adapter.getCount(); ++i) {
            FormRecord r = (FormRecord)adapter.getItem(i);
            Pair<Boolean, String> integrity = processor.verifyFormRecordIntegrity(r);
            String passfail = integrity.first ? "PASS:" : "FAIL:";
            Logger.log(AndroidLogger.TYPE_ERROR_STORAGE, passfail + integrity.second);
        }
        CommCareUtil.triggerLogSubmission(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        adapter.release();
    }

    @Override
    public int getWakeLockLevel() {
        return PowerManager.PARTIAL_WAKE_LOCK;
    }

    @Override
    public void afterTextChanged(Editable s) {
        String filtertext = s.toString();
        if (searchbox.getText() == s) {
            adapter.applyTextFilter(filtertext);
        }
        if (!isUsingActionBar()) {
            lastQueryString = filtertext;
        }
    }


    @Override
    public void beforeTextChanged(CharSequence s, int start, int count,
                                  int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void notifyPriorityLoaded(FormRecord record, boolean priority) {
    }

    @Override
    public void notifyLoaded() {
        enableSearch();
    }

    @Override
    public CustomProgressDialog generateProgressDialog(int taskId) {
        String title, message;
        switch (taskId) {
            case DataPullTask.DATA_PULL_TASK_ID:
                title = "Fetching Old Forms";
                message = "Connecting to server...";
                break;
            case ArchivedFormRemoteRestore.CLEANUP_ID:
                title = "Fetching Old Forms";
                message = "Forms downloaded. Processing...";
                break;
            case PurgeStaleArchivedFormsTask.PURGE_STALE_ARCHIVED_FORMS_TASK_ID:
                title = Localization.get("form.archive.purge.title");
                message = Localization.get("form.archive.purge.message");
                break;
            default:
                Log.w(TAG, "taskId passed to generateProgressDialog does not match "
                        + "any valid possibilities in FormRecordListActivity");
                return null;
        }
        return CustomProgressDialog.newInstance(title, message, taskId);
    }

    @Override
    public void handleTaskUpdate(Void... updateVals) {
    }

    /**
     * Archived form purging task complete, stop blocking user
     */
    @Override
    public void handleTaskCompletion(Void result) {
        dismissProgressDialog();

        // reload form list to make sure purged forms aren't shown
        if (adapter != null) {
            adapter.resetRecords();
        }
    }

    /**
     * Archived form purging task cancelled, stop blocking user
     */
    @Override
    public void handleTaskCancellation() {
        dismissProgressDialog();
    }
}
