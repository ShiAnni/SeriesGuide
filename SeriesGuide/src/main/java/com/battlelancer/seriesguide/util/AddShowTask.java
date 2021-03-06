package com.battlelancer.seriesguide.util;

import android.content.Context;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.widget.Toast;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.SgApp;
import com.battlelancer.seriesguide.backend.HexagonTools;
import com.battlelancer.seriesguide.backend.settings.HexagonSettings;
import com.battlelancer.seriesguide.items.SearchResult;
import com.battlelancer.seriesguide.settings.TraktCredentials;
import com.battlelancer.seriesguide.settings.TraktSettings;
import com.battlelancer.seriesguide.thetvdbapi.TvdbException;
import com.battlelancer.seriesguide.thetvdbapi.TvdbTools;
import com.battlelancer.seriesguide.traktapi.SgTrakt;
import com.uwetrottmann.androidutils.AndroidUtils;
import com.uwetrottmann.trakt5.entities.BaseShow;
import com.uwetrottmann.trakt5.enums.Extended;
import com.uwetrottmann.trakt5.services.Sync;
import dagger.Lazy;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import javax.inject.Inject;
import org.greenrobot.eventbus.EventBus;
import retrofit2.Response;
import timber.log.Timber;

/**
 * Adds shows to the local database, tries to get watched and collected episodes if a trakt account
 * is connected.
 */
public class AddShowTask extends AsyncTask<Void, Integer, Void> {

    public class OnShowAddedEvent {
        public final boolean successful;
        /** Is -1 if add task was aborted. */
        public final int showTvdbId;
        private final String message;

        public OnShowAddedEvent(int showTvdbId, String message, boolean successful) {
            this.showTvdbId = showTvdbId;
            this.message = message;
            this.successful = successful;
        }

        /**
         * Sets the message to null and successful to true to indicate a single show was added.
         */
        public OnShowAddedEvent(int showTvdbId) {
            this(showTvdbId, null, true);
        }

        /**
         * Sets successful to false and TVDB id to -1 to indicate the task was aborted.
         */
        public OnShowAddedEvent(String message) {
            this(-1, message, false);
        }

        public void handle(Context context) {
            if (message != null) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        }
    }

    private static final int PROGRESS_EXISTS = 0;
    private static final int PROGRESS_SUCCESS = 1;
    private static final int PROGRESS_ERROR = 2;
    private static final int RESULT_OFFLINE = 3;
    private static final int RESULT_TRAKT_API_ERROR = 4;
    private static final int RESULT_TRAKT_AUTH_ERROR = 5;

    private final SgApp app;
    private final LinkedList<SearchResult> addQueue = new LinkedList<>();

    @Inject Lazy<Sync> traktSync;
    private boolean isFinishedAddingShows = false;
    private boolean isSilentMode;
    private boolean isMergingShows;
    private String currentShowName;
    private int currentShowTvdbId;

    public AddShowTask(SgApp app, List<SearchResult> shows, boolean isSilentMode,
            boolean isMergingShows) {
        this.app = app;
        app.getServicesComponent().inject(this);
        addQueue.addAll(shows);
        this.isSilentMode = isSilentMode;
        this.isMergingShows = isMergingShows;
    }

    /**
     * Adds shows to the add queue. If this returns false, the shows were not added because the task
     * is finishing up. Create a new one instead.
     */
    public boolean addShows(List<SearchResult> show, boolean isSilentMode, boolean isMergingShows) {
        if (isFinishedAddingShows) {
            Timber.d("addShows: failed, already finishing up.");
            return false;
        } else {
            this.isSilentMode = isSilentMode;
            // never reset isMergingShows once true, so merged flag is correctly set on completion
            this.isMergingShows = this.isMergingShows || isMergingShows;
            addQueue.addAll(show);
            Timber.d("addShows: added shows to queue.");
            return true;
        }
    }

    @Override
    protected Void doInBackground(Void... params) {
        Timber.d("Starting to add shows...");

        // don't even get started
        if (addQueue.isEmpty()) {
            Timber.d("Finished. Queue was empty.");
            return null;
        }

        // set values required for progress update
        SearchResult nextShow = addQueue.peek();
        currentShowName = nextShow.title;
        currentShowTvdbId = nextShow.tvdbid;

        if (!AndroidUtils.isNetworkConnected(app)) {
            Timber.d("Finished. No internet connection.");
            publishProgress(RESULT_OFFLINE);
            return null;
        }

        if (isCancelled()) {
            Timber.d("Finished. Cancelled.");
            return null;
        }

        // if not connected to Hexagon, get episodes from trakt
        HashMap<Integer, BaseShow> traktCollection = null;
        HashMap<Integer, BaseShow> traktWatched = null;
        if (!HexagonTools.isSignedIn(app) && TraktCredentials.get(app).hasCredentials()) {
            Timber.d("Getting watched and collected episodes from trakt.");
            // get collection
            HashMap<Integer, BaseShow> traktShows = getTraktShows("get collection", true);
            if (traktShows == null) {
                return null; // can not get collected state from trakt, give up.
            }
            traktCollection = traktShows;
            // get watched
            traktShows = getTraktShows("get watched", false);
            if (traktShows == null) {
                return null; // can not get watched state from trakt, give up.
            }
            traktWatched = traktShows;
        }

        int result;
        boolean addedAtLeastOneShow = false;
        boolean failedMergingShows = false;
        while (!addQueue.isEmpty()) {
            Timber.d("Starting to add next show...");
            if (isCancelled()) {
                Timber.d("Finished. Cancelled.");
                // only cancelled on config change, so don't rebuild fts
                // table yet
                return null;
            }

            nextShow = addQueue.removeFirst();
            // set values required for progress update
            currentShowName = nextShow.title;
            currentShowTvdbId = nextShow.tvdbid;

            if (!AndroidUtils.isNetworkConnected(app)) {
                Timber.d("Finished. No connection.");
                publishProgress(RESULT_OFFLINE);
                failedMergingShows = true;
                break;
            }

            try {
                boolean addedShow = TvdbTools.getInstance(app)
                        .addShow(nextShow.tvdbid, nextShow.language, traktCollection, traktWatched);
                result = addedShow ? PROGRESS_SUCCESS : PROGRESS_EXISTS;
                addedAtLeastOneShow = addedShow
                        || addedAtLeastOneShow; // do not overwrite previous success
            } catch (TvdbException e) {
                // prevent a hexagon merge from failing if a show can not be added
                // because it does not exist (any longer)
                if (!(isMergingShows && e.getItemDoesNotExist())) {
                    failedMergingShows = true;
                }
                result = PROGRESS_ERROR;
                Timber.e(e, "Adding show failed");
            }

            publishProgress(result);
            Timber.d("Finished adding show. (Result code: %s)", result);
        }

        isFinishedAddingShows = true;

        // when merging shows down from Hexagon, set success flag
        if (isMergingShows && !failedMergingShows) {
            HexagonSettings.setHasMergedShows(app, true);
        }

        if (addedAtLeastOneShow) {
            // make sure the next sync will download all ratings
            PreferenceManager.getDefaultSharedPreferences(app).edit()
                    .putLong(TraktSettings.KEY_LAST_SHOWS_RATED_AT, 0)
                    .putLong(TraktSettings.KEY_LAST_EPISODES_RATED_AT, 0)
                    .apply();

            // renew FTS3 table
            Timber.d("Renewing search table.");
            DBUtils.rebuildFtsTable(app);
        }

        Timber.d("Finished adding shows.");
        return null;
    }

    @Override
    protected void onProgressUpdate(Integer... values) {
        if (isSilentMode) {
            Timber.d("SILENT MODE: do not show progress toast");
            return;
        }

        OnShowAddedEvent event = null;
        switch (values[0]) {
            case PROGRESS_SUCCESS:
                // do nothing, user will see show added to show list
                event = new OnShowAddedEvent(currentShowTvdbId);
                break;
            case PROGRESS_EXISTS:
                event = new OnShowAddedEvent(currentShowTvdbId,
                        app.getString(R.string.add_already_exists, currentShowName), true);
                break;
            case PROGRESS_ERROR:
                event = new OnShowAddedEvent(currentShowTvdbId,
                        app.getString(R.string.add_error, currentShowName), false);
                break;
            case RESULT_OFFLINE:
                event = new OnShowAddedEvent(app.getString(R.string.offline));
                break;
            case RESULT_TRAKT_API_ERROR:
                event = new OnShowAddedEvent(
                        app.getString(R.string.api_error_generic, app.getString(R.string.trakt)));
                break;
            case RESULT_TRAKT_AUTH_ERROR:
                event = new OnShowAddedEvent(app.getString(R.string.trakt_error_credentials));
                break;
        }

        if (event != null) {
            EventBus.getDefault().post(event);
        }
    }

    @Nullable
    private HashMap<Integer, BaseShow> getTraktShows(String action,
            boolean isCollectionNotWatched) {
        try {
            Response<List<BaseShow>> response;
            if (isCollectionNotWatched) {
                response = traktSync.get().collectionShows(Extended.DEFAULT_MIN).execute();
            } else {
                response = traktSync.get().watchedShows(Extended.DEFAULT_MIN).execute();
            }
            if (response.isSuccessful()) {
                return TraktTools.buildTraktShowsMap(response.body());
            } else {
                if (SgTrakt.isUnauthorized(app, response)) {
                    publishProgress(RESULT_TRAKT_AUTH_ERROR);
                    return null;
                }
                SgTrakt.trackFailedRequest(app, action, response);
            }
        } catch (IOException e) {
            SgTrakt.trackFailedRequest(app, action, e);
        }
        publishProgress(RESULT_TRAKT_API_ERROR);
        return null;
    }
}
