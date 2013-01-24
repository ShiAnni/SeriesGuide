/*
 * Copyright 2013 Uwe Trottmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.battlelancer.seriesguide.util;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.battlelancer.seriesguide.provider.SeriesContract.Episodes;
import com.battlelancer.seriesguide.util.FlagTapedTask.Flag;
import com.jakewharton.trakt.ServiceManager;
import com.jakewharton.trakt.services.ShowService;
import com.uwetrottmann.androidutils.AndroidUtils;
import com.uwetrottmann.seriesguide.R;

import java.util.List;

/**
 * Helps flag episodes in the local database and on trakt.tv.
 */
public class FlagTask extends AsyncTask<Void, Integer, Integer> {

    private static final int FAILED = -1;

    private static final int SUCCESS = 0;

    public interface OnFlagListener {
        /**
         * Called if flags were sent to trakt or failed to got sent and were not
         * set locally. If trakt is not used, called once flags are set in the
         * local database.
         */
        public void onFlagCompleted(FlagAction action, int showId, int itemId, boolean isSuccessful);
    }

    public enum FlagAction {
        EPISODE_WATCHED,

        EPISODE_COLLECTED,

        EPISODE_WATCHED_PREVIOUS,

        SEASON_WATCHED,

        SHOW_WATCHED;
    }

    private Context mContext;

    private int mShowId;

    private int mItemId;

    private int mSeason;

    private int mEpisode;

    private long mFirstAired;

    private boolean mIsFlag;

    private FlagAction mAction;

    private OnFlagListener mListener;

    private boolean mIsTraktInvolved;

    public FlagTask(Context context, int showId, OnFlagListener listener) {
        mContext = context;
        mShowId = showId;
        mListener = listener;
    }

    /**
     * Set the item id of the episode, season or show.
     */
    public FlagTask setItemId(int itemId) {
        mItemId = itemId;
        return this;
    }

    /**
     * Set whether to add or remove the flag. This value is ignored when using
     * {@link #episodeWatchedPrevious()} as it always adds the watched flag.
     */
    public FlagTask setFlag(boolean flag) {
        mIsFlag = flag;
        return this;
    }

    public FlagTask episodeWatched(int seasonNumber, int episodeNumber) {
        mSeason = seasonNumber;
        mEpisode = episodeNumber;
        mAction = FlagAction.EPISODE_WATCHED;
        return this;
    }

    public FlagTask episodeCollected(int seasonNumber, int episodeNumber) {
        mSeason = seasonNumber;
        mEpisode = episodeNumber;
        mAction = FlagAction.EPISODE_COLLECTED;
        return this;
    }

    /**
     * Flag all episodes previous to this one as watched. Previous in terms of
     * air date.
     * 
     * @param firstaired
     */
    public FlagTask episodeWatchedPrevious(long firstaired) {
        mFirstAired = firstaired;
        mAction = FlagAction.EPISODE_WATCHED_PREVIOUS;
        return this;
    }

    public FlagTask seasonWatched(int seasonNumber) {
        mSeason = seasonNumber;
        mEpisode = -1;
        mAction = FlagAction.SEASON_WATCHED;
        return this;
    }

    public FlagTask showWatched() {
        mAction = FlagAction.SHOW_WATCHED;
        return this;
    }

    /**
     * Run the task on the thread pool.
     */
    public void execute() {
        AndroidUtils.executeAsyncTask(this, new Void[] {});
    }

    @Override
    protected Integer doInBackground(Void... params) {
        // check for valid trakt credentials
        mIsTraktInvolved = ServiceUtils.isTraktCredentialsValid(mContext);

        // prepare trakt stuff
        if (mIsTraktInvolved) {
            ServiceManager manager = ServiceUtils.getTraktServiceManagerWithAuth(mContext, false);
            if (manager == null) {
                return FAILED;
            }

            List<Flag> episodes = Lists.newArrayList();
            ShowService showService = manager.showService();
            switch (mAction) {
                case EPISODE_WATCHED:
                case EPISODE_COLLECTED:
                    // flag a single episode
                    episodes.add(new Flag(mSeason, mEpisode));
                    break;
                case SEASON_WATCHED:
                    // flag a whole season
                    if (mIsFlag) {
                        episodes.add(new Flag(mSeason, -1));
                    } else {
                        // only for removing flags we need single episodes
                        insertEpisodeFlags(episodes);
                    }
                    break;
                case SHOW_WATCHED:
                    // flag a whole show
                    if (!mIsFlag) {
                        // only for removing flags we need single episodes
                        insertEpisodeFlags(episodes);
                    }
                    break;
                case EPISODE_WATCHED_PREVIOUS:
                    // flag episodes up to one episode
                    insertEpisodeFlags(episodes);
                    break;
            }

            // Add a new taped flag task to the tape queue
            FlagTapedTaskQueue.getInstance(mContext).add(
                    new FlagTapedTask(showService, mAction, mShowId, episodes, mIsFlag));
        }

        // always update local database
        updateDatabase(mItemId);

        return SUCCESS;
    }

    private void insertEpisodeFlags(List<Flag> episodes) {
        // determine uri
        Uri uri;
        String selection = null;
        switch (mAction) {
            case SEASON_WATCHED:
                uri = Episodes.buildEpisodesOfSeasonUri(String.valueOf(mItemId));
                break;
            case SHOW_WATCHED:
                uri = Episodes.buildEpisodesOfShowUri(String.valueOf(mShowId));
                break;
            case EPISODE_WATCHED_PREVIOUS:
                if (mFirstAired <= 0) {
                    return;
                }
                uri = Episodes.buildEpisodesOfShowUri(String.valueOf(mShowId));
                selection = Episodes.FIRSTAIREDMS + "<" + mFirstAired + " AND "
                        + Episodes.FIRSTAIREDMS
                        + ">0";
            default:
                return;
        }

        // query and add episodes to list
        final Cursor episodeCursor = mContext.getContentResolver().query(
                uri,
                new String[] {
                        Episodes.SEASON, Episodes.NUMBER
                }, selection, null, null);
        if (episodeCursor != null) {
            while (episodeCursor.moveToNext()) {
                episodes.add(new Flag(episodeCursor.getInt(0), episodeCursor.getInt(1)));
            }
            episodeCursor.close();
        }
    }

    private void updateDatabase(int itemId) {
        // determine flag column
        String column;
        switch (mAction) {
            case EPISODE_WATCHED:
            case EPISODE_WATCHED_PREVIOUS:
            case SEASON_WATCHED:
            case SHOW_WATCHED:
                column = Episodes.WATCHED;
                break;
            case EPISODE_COLLECTED:
                column = Episodes.COLLECTED;
                break;
            default:
                return;
        }

        // determine query uri
        Uri uri;
        switch (mAction) {
            case EPISODE_WATCHED:
            case EPISODE_COLLECTED:
                uri = Episodes.buildEpisodeUri(String.valueOf(itemId));
                break;
            case SEASON_WATCHED:
                uri = Episodes.buildEpisodesOfSeasonUri(String.valueOf(itemId));
                break;
            case EPISODE_WATCHED_PREVIOUS:
            case SHOW_WATCHED:
                uri = Episodes.buildEpisodesOfShowUri(String.valueOf(mShowId));
                break;
            default:
                return;
        }

        // build and execute query
        ContentValues values = new ContentValues();
        if (mAction == FlagAction.EPISODE_WATCHED_PREVIOUS) {
            // mark all previously aired episodes
            if (mFirstAired > 0) {
                values.put(column, true);
                mContext.getContentResolver().update(
                        uri,
                        values,
                        Episodes.FIRSTAIREDMS + "<" + mFirstAired + " AND " + Episodes.FIRSTAIREDMS
                                + ">0", null);
            }
        } else {
            values.put(column, mIsFlag);
            mContext.getContentResolver().update(uri, values, null, null);
        }

        // notify the content provider for udpates
        mContext.getContentResolver().notifyChange(Episodes.CONTENT_URI, null);
    }

    @Override
    protected void onPostExecute(Integer result) {
        // display a small toast if submission to trakt was successful
        if (mIsTraktInvolved) {
            int message;
            switch (mAction) {
                case EPISODE_WATCHED:
                case SEASON_WATCHED:
                    if (mIsFlag) {
                        message = R.string.trakt_seen;
                    } else {
                        message = R.string.trakt_notseen;
                    }
                    break;
                case EPISODE_COLLECTED:
                    if (mIsFlag) {
                        message = R.string.trakt_collected;
                    } else {
                        message = R.string.trakt_notcollected;
                    }
                    break;
                default:
                    message = 0;
                    break;
            }

            int status = 0;
            int duration = 0;
            switch (result) {
                case SUCCESS: {
                    status = R.string.trakt_submitsuccess;
                    duration = Toast.LENGTH_SHORT;
                    break;
                }
                case FAILED: {
                    status = R.string.trakt_submitfailed;
                    duration = Toast.LENGTH_LONG;
                    break;
                }
            }

            if (mAction == FlagAction.SHOW_WATCHED
                    || mAction == FlagAction.EPISODE_WATCHED_PREVIOUS) {
                // simple ack
                Toast.makeText(mContext,
                        mContext.getString(status),
                        duration).show();
            } else {
                // detailed ack
                final SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(mContext);
                String number = Utils.getEpisodeNumber(prefs, mSeason, mEpisode);
                Toast.makeText(mContext,
                        mContext.getString(message, number) + " " + mContext.getString(status),
                        duration).show();
            }
        }

        if (mListener != null) {
            mListener.onFlagCompleted(mAction, mShowId, mItemId, result == SUCCESS ? true : false);
        }
    }
}
