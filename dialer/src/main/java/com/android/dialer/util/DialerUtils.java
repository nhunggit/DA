/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.dialer.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.android.contacts.common.CallUtil;
import com.android.contacts.common.ContactsUtils;
import com.android.contacts.common.interactions.TouchPointManager;
import com.android.dialer.R;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * General purpose utility methods for the Dialer.
 */
public class DialerUtils {

    private static final String PREFS_MESSAGE = "video_call_welcome";
    private static final String KEY_STATE = "message-repeat";
    private static final String KEY_FIRST_LAUNCH = "first-launch";

    private static final String SEND_EVENT_BTALK_ACTION = "bkav.intent.action.SEND_EVENT_BTALK";
    private static final String EXTRA_EVENT_NAME = "extra_event_name";
    /**
     * Attempts to start an activity and displays a toast with the default error message if the
     * activity is not found, instead of throwing an exception.
     *
     * @param context to start the activity with.
     * @param intent to start the activity with.
     */
    public static void startActivityWithErrorToast(Context context, Intent intent) {
        startActivityWithErrorToast(context, intent, R.string.activity_not_available);
    }

    // Bkav HienDTk: fix loi - BOS-3181 - start
    // Bkav HienDTk: gui broadcast len de thong ke xem nguoi dung su dung tab nao nhieu nhat de goi dien or vao app
    public static void sendBroadcastCount(Context context, String value, int isCall){
        Intent intent = new Intent();
        intent.setAction(SEND_EVENT_BTALK_ACTION);
        intent.setComponent(new ComponentName("bkav.android.staticsticalbphone", "bkav.android.staticsticalbphone.ReportDataReceiver"));
        if(isCall == 1){
            intent.putExtra(EXTRA_EVENT_NAME, value);

        }else {
            intent.putExtra(EXTRA_EVENT_NAME, value);

        }

        context.sendBroadcast(intent);
    }
    /**
     * HienDTk: convert tab duoc chon sang string
     */
    public static String convertTabSelectForOpen(int tab){
        String tabchoose = null;
        if(tab == 0){
            tabchoose = "tab_phone";
        }
        if(tab == 1){
            tabchoose = "tab_messages";
        }
        if(tab == 2){
            tabchoose = "tab_calllog";
        }
        if(tab == 3){
            tabchoose = "tab_contacts";
        }
        return tabchoose;
    }

    public static String convertTabSeletedForCall(int tab){
        String tabchoose = null;
        if(tab == 0){
            tabchoose = "tab_phone_call";
        }
        if(tab == 1){
            tabchoose = "tab_messages_call";
        }
        if(tab == 2){
            tabchoose = "tab_calllog_call";
        }
        if(tab == 3){
            tabchoose = "tab_contacts_call";
        }
        return tabchoose;
    }
    // Bkav HienDTk: fix loi - BOS-3181 - End

    /**
     * Attempts to start an activity and displays a toast with a provided error message if the
     * activity is not found, instead of throwing an exception.
     *
     * @param context to start the activity with.
     * @param intent to start the activity with.
     * @param msgId Resource ID of the string to display in an error message if the activity is
     *              not found.
     */
    public static void startActivityWithErrorToast(Context context, Intent intent, int msgId) {
        try {
            if (IntentUtil.CALL_ACTION.equals(intent.getAction())){
                // Bkav TienNAb: fix loi khong duoc so dien thoai khi bat dau bang "84"
                String data = intent.getData().toString();
                if (data.startsWith("tel:")){
                    String number = data.substring(4).trim();
                    if (number.startsWith("84")){
                        number = "+" + number;
                        intent = CallUtil.getCallIntent(number);
                    }
                }
            }

            if ((IntentUtil.CALL_ACTION.equals(intent.getAction())
                            && context instanceof Activity)) {

                // All dialer-initiated calls should pass the touch point to the InCallUI
                Point touchPoint = TouchPointManager.getInstance().getPoint();
                if (touchPoint.x != 0 || touchPoint.y != 0) {
                    Bundle extras;
                    // Make sure to not accidentally clobber any existing extras
                    if (intent.hasExtra(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)) {
                        extras = intent.getParcelableExtra(
                                TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS);
                    } else {
                        extras = new Bundle();
                    }
                    extras.putParcelable(TouchPointManager.TOUCH_POINT, touchPoint);
                    intent.putExtra(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, extras);
                }

                final boolean hasCallPermission = TelecomUtil.placeCall((Activity) context, intent);
                if (!hasCallPermission) {
                    // TODO: Make calling activity show request permission dialog and handle
                    // callback results appropriately.
                    Toast.makeText(context, "Cannot place call without Phone permission",
                            Toast.LENGTH_SHORT);
                }
            } else {
                // Bkav HuyNQN fix loi sau khi nang version sdk 28
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(intent);
            }
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, msgId, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Returns the component name to use in order to send an SMS using the default SMS application,
     * or null if none exists.
     */
    public static ComponentName getSmsComponent(Context context) {
        String smsPackage = Telephony.Sms.getDefaultSmsPackage(context);
        if (smsPackage != null) {
            final PackageManager packageManager = context.getPackageManager();
            final Intent intent = new Intent(Intent.ACTION_SENDTO,
                    Uri.fromParts(ContactsUtils.SCHEME_SMSTO, "", null));
            final List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(intent, 0);
            for (ResolveInfo resolveInfo : resolveInfos) {
                if (smsPackage.equals(resolveInfo.activityInfo.packageName)) {
                    return new ComponentName(smsPackage, resolveInfo.activityInfo.name);
                }
            }
        }
        return null;
    }

    /**
     * Closes an {@link AutoCloseable}, silently ignoring any checked exceptions. Does nothing if
     * null.
     *
     * @param closeable to close.
     */
    public static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Joins a list of {@link CharSequence} into a single {@link CharSequence} seperated by a
     * localized delimiter such as ", ".
     *
     * @param resources Resources used to get list delimiter.
     * @param list List of char sequences to join.
     * @return Joined char sequences.
     */
    public static CharSequence join(Resources resources, Iterable<CharSequence> list) {
        StringBuilder sb = new StringBuilder();
        final BidiFormatter formatter = BidiFormatter.getInstance();
        final CharSequence separator = resources.getString(R.string.list_delimeter);

        Iterator<CharSequence> itr = list.iterator();
        boolean firstTime = true;
        while (itr.hasNext()) {
            if (firstTime) {
                firstTime = false;
            } else {
                sb.append(separator);
            }
            // Unicode wrap the elements of the list to respect RTL for individual strings.
            sb.append(formatter.unicodeWrap(
                    itr.next().toString(), TextDirectionHeuristics.FIRSTSTRONG_LTR));
        }

        // Unicode wrap the joined value, to respect locale's RTL ordering for the whole list.
        return formatter.unicodeWrap(sb.toString());
    }

    /**
     * @return True if the application is currently in RTL mode.
     */
    public static boolean isRtl() {
        return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) ==
            View.LAYOUT_DIRECTION_RTL;
    }

    public static void showInputMethod(View view) {
        final InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, 0);
        }
    }

    public static void hideInputMethod(View view) {
        final InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }


    /**
     * @return true if it is the first launch.
     */
    public static boolean isFirstLaunch(Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(
                PREFS_MESSAGE, Context.MODE_PRIVATE);
        boolean isFirstLaunch = prefs.getBoolean(KEY_FIRST_LAUNCH, true);
        if (isFirstLaunch) {
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();
        }
        return isFirstLaunch;
    }

    /**
     * @return true if the Welcome Screen shall be presented to the user, false otherwise.
     */
    public static boolean canShowWelcomeScreen(Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(
                PREFS_MESSAGE, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_STATE, false);
    }


    /**
     * Save the state of Welcome Screen.
     *
     *@param context
     *@param show if the Welcome Screen should be presented
     */
    public static void setShowingState(Context context, boolean show) {
        final SharedPreferences prefs = context.getSharedPreferences(
                PREFS_MESSAGE, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_STATE, show).apply();
    }

    /**
     * @return true if calllog inserted earlier when dial a ConfURI call.
     */
    public static boolean isConferenceURICallLog(String number, String postDialDigits) {
        return (number == null || number.contains(";") || number.contains(",")) &&
                (postDialDigits == null || postDialDigits.equals(""));
    }

    private static final String PACKAGE_DIALER = "com.android.dialer";
    // Bkav HuyNQN kiem tra xem co phai dialer cua bkav hay cai dialer ngoai
    public static boolean isDialerBkav(Context context) {
        String packageDefaultDialer = getDefaultDialerApplication(context);
        return PACKAGE_DIALER.equals(packageDefaultDialer);
    }

    private static String getDefaultDialerApplication(Context context) {
        try {
            Class classDefault = Class.forName("android.telecom.DefaultDialerManager");
            Method getDefaultDialerApplication = classDefault.getDeclaredMethod("getDefaultDialerApplication", Context.class);
            String pkgDialer = (String) getDefaultDialerApplication.invoke(null, context);
            return pkgDialer;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}
