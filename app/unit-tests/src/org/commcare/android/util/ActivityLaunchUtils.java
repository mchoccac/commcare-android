package org.commcare.android.util;

import org.commcare.CommCareApplication;
import org.commcare.activities.StandardHomeActivity;
import org.commcare.android.mocks.FormAndDataSyncerFake;
import org.commcare.models.AndroidSessionWrapper;
import org.commcare.session.CommCareSession;
import org.commcare.session.SessionNavigator;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowActivity;

/**
 * @author Phillip Mates (pmates@dimagi.com)
 */
public class ActivityLaunchUtils {
    public static ShadowActivity buildHomeActivityForFormEntryLaunch(String sessionCommand) {
        AndroidSessionWrapper sessionWrapper =
                CommCareApplication.instance().getCurrentSessionWrapper();
        CommCareSession session = sessionWrapper.getSession();
        session.setCommand(sessionCommand);
        return buildHomeActivity();
    }

    public static ShadowActivity buildHomeActivity() {
        StandardHomeActivity homeActivity =
                Robolectric.buildActivity(StandardHomeActivity.class).create().get();
        // make sure we don't actually submit forms by using a fake form submitter
        homeActivity.setFormAndDataSyncer(new FormAndDataSyncerFake());
        SessionNavigator sessionNavigator = homeActivity.getSessionNavigator();
        sessionNavigator.startNextSessionStep();
        return Shadows.shadowOf(homeActivity);
    }
}
