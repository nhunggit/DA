/*
 * Copyright (C) 2011 The Android Open Source Project
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
 */

package com.android.dialer.calllog;

import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteDiskIOException;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteFullException;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.CallLog.Calls;
import android.provider.VoicemailContract.Status;
import android.provider.VoicemailContract.Voicemails;
import android.util.Log;

import com.android.contacts.common.compat.SdkVersionOverride;
import com.android.contacts.common.database.NoNullCursorAsyncQueryHandler;
import com.android.contacts.common.util.PermissionsUtil;
import com.android.dialer.database.VoicemailArchiveContract;
import com.android.dialer.util.AppCompatConstants;
import com.android.dialer.util.TelecomUtil;
import com.android.dialer.voicemail.VoicemailStatusHelperImpl;

import com.android.incallui.Call;
import com.google.common.collect.Lists;

import java.lang.ref.WeakReference;
import java.util.List;

/** Handles asynchronous queries to the call log. */
public class CallLogQueryHandler extends NoNullCursorAsyncQueryHandler {
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private static final String TAG = "CallLogQueryHandler";
    private static final int NUM_LOGS_TO_DISPLAY = 1000;

    /** The token for the query to fetch the old entries from the call log. */
    private static final int QUERY_CALLLOG_TOKEN = 54;
    /** The token for the query to mark all missed calls as old after seeing the call log. */
    private static final int UPDATE_MARK_AS_OLD_TOKEN = 55;
    /** The token for the query to mark all missed calls as read after seeing the call log. */
    private static final int UPDATE_MARK_MISSED_CALL_AS_READ_TOKEN = 56;
    /** The token for the query to fetch voicemail status messages. */
    private static final int QUERY_VOICEMAIL_STATUS_TOKEN = 57;
    /** The token for the query to fetch the number of unread voicemails. */
    private static final int QUERY_VOICEMAIL_UNREAD_COUNT_TOKEN = 58;
    /** The token for the query to fetch the number of missed calls. */
    private static final int QUERY_MISSED_CALLS_UNREAD_COUNT_TOKEN = 59;
    /** The oken for the query to fetch the archived voicemails. */
    private static final int QUERY_VOICEMAIL_ARCHIVE = 60;

    private final int mLogLimit;

    /**
     * Call type similar to Calls.INCOMING_TYPE used to specify all types instead of one particular
     * type. Exception: excludes Calls.VOICEMAIL_TYPE.
     */
    public static final int CALL_TYPE_ALL = -1;

    /**
     * To specify all slots.
     */
    public static final int CALL_SUB_ALL = -1;

    private final WeakReference<Listener> mListener;

    private final Context mContext;

    // Bkav HuyNQN cot luu duong dan file ghi am cuoc goi
    public static final String RECORD_CALL_DATA = "record_call_data";
    //Bkav QuangNDb them cac cot ghi am cuoc goi
    public static final String LABEL_BMS_SPAM_VN = "lable_bms_spam_vn";// trong rom viet sai chinh ta tu label thanh lable
    public static final String LABEL_BMS_SPAM_ENG = "lable_bms_spam_eng";// trong rom viet sai chinh ta tu label thanh lable


    /**
     * Simple handler that wraps background calls to catch
     * {@link SQLiteException}, such as when the disk is full.
     */
    protected class CatchingWorkerHandler extends WorkerHandler {
        public CatchingWorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                // Perform same query while catching any exceptions
                super.handleMessage(msg);
            } catch (SQLiteDiskIOException e) {
                Log.w(TAG, "Exception on background worker thread", e);
            } catch (SQLiteFullException e) {
                Log.w(TAG, "Exception on background worker thread", e);
            } catch (SQLiteDatabaseCorruptException e) {
                Log.w(TAG, "Exception on background worker thread", e);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "ContactsProvider not present on device", e);
            } catch (SecurityException e) {
                // Shouldn't happen if we are protecting the entry points correctly,
                // but just in case.
                Log.w(TAG, "No permission to access ContactsProvider.", e);
            }
        }
    }

    @Override
    protected Handler createHandler(Looper looper) {
        // Provide our special handler that catches exceptions
        return new CatchingWorkerHandler(looper);
    }

    public CallLogQueryHandler(Context context, ContentResolver contentResolver,
            Listener listener) {
        this(context, contentResolver, listener, -1);
    }

    public CallLogQueryHandler(Context context, ContentResolver contentResolver, Listener listener,
            int limit) {
        super(contentResolver);
        mContext = context.getApplicationContext();
        mListener = new WeakReference<Listener>(listener);
        mLogLimit = limit;
    }

    /**
     * Fetch all the voicemails in the voicemail archive.
     */
    public void fetchVoicemailArchive() {
        startQuery(QUERY_VOICEMAIL_ARCHIVE, null,
                VoicemailArchiveContract.VoicemailArchive.CONTENT_URI,
                null, VoicemailArchiveContract.VoicemailArchive.ARCHIVED + " = 1", null,
                VoicemailArchiveContract.VoicemailArchive.DATE + " DESC");
    }


    /**
     * Fetches the list of calls from the call log for a given type.
     * This call ignores the new or old state.
     * <p>
     * It will asynchronously update the content of the list view when the fetch completes.
     */
    public void fetchCalls(int callType, long newerThan) {
        cancelFetch();
        if (PermissionsUtil.hasPhonePermissions(mContext)) {
            fetchCalls(QUERY_CALLLOG_TOKEN, callType, false /* newOnly */, newerThan);
        } else {
            updateAdapterData(null);
        }
    }

    public void fetchCalls(int callType, long newerThan, int sub) {
        cancelFetch();
        if (PermissionsUtil.hasPhonePermissions(mContext)) {
            fetchCalls(QUERY_CALLLOG_TOKEN, callType, false /* newOnly */, newerThan, sub);
        } else {
            updateAdapterData(null);
        }
    }

    public void fetchCalls(int callType) {
        fetchCalls(callType, 0);
    }

    public void fetchCalls(String filter) {
        cancelFetch();
        fetchCalls(QUERY_CALLLOG_TOKEN, filter);
    }

    public void fetchCalls(int token, String filter) {
        String selection = "(" + Calls.NUMBER + " like '%" + filter
                + "%'  or  " + Calls.CACHED_NAME + " like '%" + filter + "%' )";

        startQuery(token, null, Calls.CONTENT_URI_WITH_VOICEMAIL,
                CallLogQuery._PROJECTION, selection, null,
                Calls.DEFAULT_SORT_ORDER);
    }

    public void fetchVoicemailStatus() {
        if (TelecomUtil.hasReadWriteVoicemailPermissions(mContext)) {
            startQuery(QUERY_VOICEMAIL_STATUS_TOKEN, null, Status.CONTENT_URI,
                    VoicemailStatusHelperImpl.PROJECTION, null, null, null);
        }
    }

    public void fetchVoicemailUnreadCount() {
        if (TelecomUtil.hasReadWriteVoicemailPermissions(mContext)) {
            // Only count voicemails that have not been read and have not been deleted.
            startQuery(QUERY_VOICEMAIL_UNREAD_COUNT_TOKEN, null, Voicemails.CONTENT_URI,
                new String[] { Voicemails._ID },
                    Voicemails.IS_READ + "=0" + " AND " + Voicemails.DELETED + "=0", null, null);
        }
    }

    /** Fetches the list of calls in the call log. */
    private void fetchCalls(int token, int callType, boolean newOnly, long newerThan) {
        StringBuilder where = new StringBuilder();
        List<String> selectionArgs = Lists.newArrayList();

        // Always hide blocked calls.
        where.append("(").append(Calls.TYPE).append(" != ?)");
        selectionArgs.add(Integer.toString(AppCompatConstants.CALLS_BLOCKED_TYPE));

        // Ignore voicemails marked as deleted
        if (SdkVersionOverride.getSdkVersion(Build.VERSION_CODES.M)
                >= Build.VERSION_CODES.M) {
            where.append(" AND (").append(Voicemails.DELETED).append(" = 0)");
        }

        if (newOnly) {
            where.append(" AND (").append(Calls.NEW).append(" = 1)");
        }

        if (callType > CALL_TYPE_ALL) {
            if (where.length() > 0) {
                where.append(" AND ");
            }

            if ((callType == Calls.INCOMING_TYPE) || (callType == Calls.OUTGOING_TYPE)) {
                where.append(String.format("(%s = ? OR %s = ? OR %s = ?)",
                        Calls.TYPE, Calls.TYPE, Calls.TYPE));
            }
            else if (callType == Calls.MISSED_TYPE) {
                where.append(String.format("(%s = ? OR %s = ? OR %s = ? OR %s = ?)",
                        Calls.TYPE, Calls.TYPE, Calls.TYPE, Calls.TYPE));
            } else {
                where.append(String.format("(%s = ?)", Calls.TYPE));
            }
            selectionArgs.add(Integer.toString(callType));
            if (callType == Calls.INCOMING_TYPE) {
                selectionArgs.add(Integer.toString(AppCompatConstants.INCOMING_IMS_TYPE));
                selectionArgs.add(Integer.toString(AppCompatConstants.INCOMING_WIFI_TYPE));
            } else if (callType == Calls.OUTGOING_TYPE) {
                selectionArgs.add(Integer.toString(AppCompatConstants.OUTGOING_IMS_TYPE));
                selectionArgs.add(Integer.toString(AppCompatConstants.OUTGOING_WIFI_TYPE));
            } else if (callType == Calls.MISSED_TYPE) {
                selectionArgs.add(Integer.toString(AppCompatConstants.MISSED_IMS_TYPE));
                selectionArgs.add(Integer.toString(AppCompatConstants.MISSED_WIFI_TYPE));
                // Anhdts them reject call
                selectionArgs.add(Integer.toString(AppCompatConstants.CALLS_REJECTED_TYPE));
            }
        } else {
            // Add a clause to fetch only items of type voicemail.
            where.append(" AND NOT ");
            where.append("(" + Calls.TYPE + " = " + AppCompatConstants.CALLS_VOICEMAIL_TYPE + ")");
        }

        // Add a clause to fetch only items newer than the requested date
        if (newerThan > 0) {
            where.append(" AND (").append(Calls.DATE).append(" > ?)");
            selectionArgs.add(Long.toString(newerThan));
        }

        // Bkav HuyNQN check search call log recoder
        if(mIsSearch){
            where.append(" AND (").append(Calls.NUMBER).append(" LIKE '%").append(mTextQuery).append("%'");
            where.append(" OR ").append(Calls.CACHED_NAME).append(" LIKE '%").append(mTextQuery).append("%' )");
        }
        // Bkav HuyNQN Lay ra cac cuoc goi duoc ghi am
        if(mIsCallLogRecorder){
            where.append(" AND ( ").append(RECORD_CALL_DATA).append(" IS NOT NULL ").append(" AND ").append(RECORD_CALL_DATA).append(" <> '')");
//            where.append(" AND ( ").append(RECORD_CALL_DATA).append(" IS NOT NULL )").append(" AND ( ").append(RECORD_CALL_DATA).append(" != '')");
        }

        final int limit = (mLogLimit == -1) ? NUM_LOGS_TO_DISPLAY : mLogLimit;
        final String selection = where.length() > 0 ? where.toString() : null;
        Uri uri = TelecomUtil.getCallLogUri(mContext).buildUpon()
                .appendQueryParameter(Calls.LIMIT_PARAM_KEY, Integer.toString(limit))
                .build();
        startQuery(token, null, uri, CallLogQuery._PROJECTION, selection, selectionArgs.toArray(
                new String[selectionArgs.size()]), Calls.DEFAULT_SORT_ORDER);
    }

    private void fetchCalls(int token, int callType, boolean newOnly,
            long newerThan, int sub) {
        // We need to check for NULL explicitly otherwise entries with where READ is NULL
        // may not match either the query or its negation.
        // We consider the calls that are not yet consumed (i.e. IS_READ = 0) as "new".
        StringBuilder where = new StringBuilder();
        List<String> selectionArgs = Lists.newArrayList();

        // Ignore voicemails marked as deleted
        where.append(Voicemails.DELETED);
        where.append(" = 0");

        if (newOnly) {
            where.append(" AND ");
            where.append(Calls.NEW);
            where.append(" = 1");
        }

        if (callType > CALL_TYPE_ALL) {
            where.append(" AND ");
            if ((callType == Calls.INCOMING_TYPE) || (callType == Calls.OUTGOING_TYPE)
                    || (callType == Calls.MISSED_TYPE)) {
                where.append(String.format("(%s = ? OR %s = ? OR %s = ?)",
                        Calls.TYPE, Calls.TYPE, Calls.TYPE));
            } else {
                // Add a clause to fetch only items of type voicemail.
                where.append(String.format("(%s = ?)", Calls.TYPE));
            }
            // Add a clause to fetch only items newer than the requested date
            selectionArgs.add(Integer.toString(callType));
            if (callType == Calls.INCOMING_TYPE) {
                selectionArgs.add(Integer.toString(AppCompatConstants.INCOMING_IMS_TYPE));
                selectionArgs.add(Integer.toString(AppCompatConstants.INCOMING_WIFI_TYPE));
            } else if (callType == Calls.OUTGOING_TYPE) {
                selectionArgs.add(Integer.toString(AppCompatConstants.OUTGOING_IMS_TYPE));
                selectionArgs.add(Integer.toString(AppCompatConstants.OUTGOING_WIFI_TYPE));
            } else if (callType == Calls.MISSED_TYPE) {
                selectionArgs.add(Integer.toString(AppCompatConstants.MISSED_IMS_TYPE));
                selectionArgs.add(Integer.toString(AppCompatConstants.MISSED_WIFI_TYPE));
            }
        } else {
            where.append(" AND NOT ");
            where.append("(" + Calls.TYPE + " = " + Calls.VOICEMAIL_TYPE + ")");
        }

        if (sub > CALL_SUB_ALL) {
            where.append(" AND ");
            where.append(String.format("(%s = ?)", Calls.PHONE_ACCOUNT_ID));
            selectionArgs.add(Integer.toString(sub));
        }

        if (newerThan > 0) {
            where.append(" AND ");
            where.append(String.format("(%s > ?)", Calls.DATE));
            selectionArgs.add(Long.toString(newerThan));
        }

        final int limit = (mLogLimit == -1) ? NUM_LOGS_TO_DISPLAY : mLogLimit;
        final String selection = where.length() > 0 ? where.toString() : null;
        Uri uri = TelecomUtil.getCallLogUri(mContext).buildUpon()
                .appendQueryParameter(Calls.LIMIT_PARAM_KEY, Integer.toString(limit))
                .build();
        startQuery(token, null, uri,
                CallLogQuery._PROJECTION, selection, selectionArgs.toArray(EMPTY_STRING_ARRAY),
                Calls.DEFAULT_SORT_ORDER);
    }


    /** Cancel any pending fetch request. */
    private void cancelFetch() {
        cancelOperation(QUERY_CALLLOG_TOKEN);
    }

    /** Updates all new calls to mark them as old. */
    public void markNewCallsAsOld() {
        if (!PermissionsUtil.hasPhonePermissions(mContext)) {
            return;
        }
        // Mark all "new" calls as not new anymore.
        StringBuilder where = new StringBuilder();
        where.append(Calls.NEW);
        where.append(" = 1");

        ContentValues values = new ContentValues(1);
        values.put(Calls.NEW, "0");

        startUpdate(UPDATE_MARK_AS_OLD_TOKEN, null, TelecomUtil.getCallLogUri(mContext),
                values, where.toString(), null);
    }

    /** Updates all missed calls to mark them as read. */
    public void markMissedCallsAsRead() {
        if (!PermissionsUtil.hasPhonePermissions(mContext)) {
            return;
        }

        ContentValues values = new ContentValues(1);
        values.put(Calls.IS_READ, "1");

        startUpdate(UPDATE_MARK_MISSED_CALL_AS_READ_TOKEN, null, Calls.CONTENT_URI, values,
                getUnreadMissedCallsQuery(), null);
    }

    /** Fetch all missed calls received since last time the tab was opened. */
    public void fetchMissedCallsUnreadCount() {
        if (!PermissionsUtil.hasPhonePermissions(mContext)) {
            return;
        }

        startQuery(QUERY_MISSED_CALLS_UNREAD_COUNT_TOKEN, null, Calls.CONTENT_URI,
                new String[]{Calls._ID}, getUnreadMissedCallsQuery(), null, null);
    }


    @Override
    protected synchronized void onNotNullableQueryComplete(int token, Object cookie,
            Cursor cursor) {
        if (cursor == null) {
            return;
        }
        try {
            if (token == QUERY_CALLLOG_TOKEN || token == QUERY_VOICEMAIL_ARCHIVE) {
                if (updateAdapterData(cursor)) {
                    cursor = null;
                }
            } else if (token == QUERY_VOICEMAIL_STATUS_TOKEN) {
                updateVoicemailStatus(cursor);
            } else if (token == QUERY_VOICEMAIL_UNREAD_COUNT_TOKEN) {
                updateVoicemailUnreadCount(cursor);
            } else if (token == QUERY_MISSED_CALLS_UNREAD_COUNT_TOKEN) {
                updateMissedCallsUnreadCount(cursor);
            } else {
                Log.w(TAG, "Unknown query completed: ignoring: " + token);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Updates the adapter in the call log fragment to show the new cursor data.
     * Returns true if the listener took ownership of the cursor.
     */
    private boolean updateAdapterData(Cursor cursor) {
        final Listener listener = mListener.get();
        if (listener != null) {
            return listener.onCallsFetched(cursor);
        }
        return false;

    }

    /**
     * @return Query string to get all unread missed calls.
     */
    private String getUnreadMissedCallsQuery() {
        StringBuilder where = new StringBuilder();
        where.append(Calls.IS_READ).append(" = 0 OR ").append(Calls.IS_READ).append(" IS NULL");
        where.append(" AND ");
        // Bkav TienNAb: Them Type MISSED_IMS_TYPE cua sim volte de truy van cuoc goi nho khi dung sim volte
        where.append(" ( ").append(Calls.TYPE).append(" = ").append(Calls.MISSED_TYPE).append(" OR ").append(Calls.TYPE).append(" = ").append(AppCompatConstants.MISSED_IMS_TYPE).append(" ) ");
        return where.toString();
    }

    private void updateVoicemailStatus(Cursor statusCursor) {
        final Listener listener = mListener.get();
        if (listener != null) {
            listener.onVoicemailStatusFetched(statusCursor);
        }
    }

    private void updateVoicemailUnreadCount(Cursor statusCursor) {
        final Listener listener = mListener.get();
        if (listener != null) {
            listener.onVoicemailUnreadCountFetched(statusCursor);
        }
    }

    private void updateMissedCallsUnreadCount(Cursor statusCursor) {
        final Listener listener = mListener.get();
        if (listener != null) {
            listener.onMissedCallsUnreadCountFetched(statusCursor);
        }
    }

    /** Listener to completion of various queries. */
    public interface Listener {
        /** Called when {@link CallLogQueryHandler#fetchVoicemailStatus()} completes. */
        void onVoicemailStatusFetched(Cursor statusCursor);

        /** Called when {@link CallLogQueryHandler#fetchVoicemailUnreadCount()} completes. */
        void onVoicemailUnreadCountFetched(Cursor cursor);

        /** Called when {@link CallLogQueryHandler#fetchMissedCallsUnreadCount()} completes. */
        void onMissedCallsUnreadCountFetched(Cursor cursor);

        /**
         * Called when {@link CallLogQueryHandler#fetchCalls(int)} complete.
         * Returns true if takes ownership of cursor.
         */
        boolean onCallsFetched(Cursor combinedCursor);
    }

    // Bkav HuyNQN check searchview bat su kien
    private boolean mIsSearch=false;

    public void setIsSearch(boolean IsSearch) {
        this.mIsSearch = IsSearch;
    }

    // Bkav HuyNQN truyen vao chuoi can tim kiem
    private String mTextQuery=null;

    public void setTextQuery(String textQuery) {
        this.mTextQuery = textQuery;
    }

    // Bkav HuyNQN xac nhan dang o fragment CallLogRecorder
    private boolean mIsCallLogRecorder=false;

    public void setIsCallLogRecorder(boolean CallLogRecorder) {
        this.mIsCallLogRecorder = CallLogRecorder;
    }
}
