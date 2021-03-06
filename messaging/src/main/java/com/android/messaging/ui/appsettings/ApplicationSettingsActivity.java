/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.messaging.ui.appsettings;

import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.RingtonePreference;
import android.preference.TwoStatePreference;
import android.provider.Settings;
import android.support.v4.app.NavUtils;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;

import com.android.messaging.R;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.LicenseActivity;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.DebugUtils;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;

public class ApplicationSettingsActivity extends BugleActionBarActivity {
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setElevationActionbar();
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        final boolean topLevel = getIntent().getBooleanExtra(
                UIIntents.UI_INTENT_EXTRA_TOP_LEVEL_SETTINGS, false);
        if (topLevel) {
            getSupportActionBar().setTitle(getString(R.string.settings_activity_title));
            // Bkav QuangNDb them ham set subid override o lop con
            initSubId();
        }

        FragmentTransaction ft = getFragmentManager().beginTransaction();
        // Bkav QuangNDb tach code doan fragment de override lai o class con
        ft.replace(android.R.id.content, getFragment());
        ft.commit();
    }



    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (super.onCreateOptionsMenu(menu)) {
            return true;
        }
        getMenuInflater().inflate(R.menu.settings_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        int i = item.getItemId();
        if (i == android.R.id.home) {
            // Bkav QuangNDb tach code doan home button press
            homeButtonPress();
            return true;
        } else if (i == R.id.action_license) {
            final Intent intent = new Intent(this, LicenseActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class ApplicationSettingsFragment extends PreferenceFragment implements
            OnSharedPreferenceChangeListener {

        private String mNotificationsEnabledPreferenceKey;
        private TwoStatePreference mNotificationsEnabledPreference;
        private String mRingtonePreferenceKey;
        private RingtonePreference mRingtonePreference;
        private Preference mVibratePreference;
        private String mSmsDisabledPrefKey;
        private Preference mSmsDisabledPreference;
        private String mSmsEnabledPrefKey;
        private Preference mSmsEnabledPreference;
        private boolean mIsSmsPreferenceClicked;
        private Preference mSettingNotificationChannel;
        private String mSettingNotificationChannelKey;

        public ApplicationSettingsFragment() {
            // Required empty constructor
        }

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            getPreferenceManager().setSharedPreferencesName(BuglePrefs.SHARED_PREFERENCES_NAME);
            // Bkav QuangNDb tach code doan khoi tao pref
            addPreferencesFromResource(getPreferenceId());

            mNotificationsEnabledPreferenceKey =
                    getString(R.string.notifications_enabled_pref_key);
            mNotificationsEnabledPreference = (TwoStatePreference) findPreference(
                    mNotificationsEnabledPreferenceKey);
            mRingtonePreferenceKey = getString(R.string.notification_sound_pref_key);
            mRingtonePreference = (RingtonePreference) findPreference(mRingtonePreferenceKey);
            mVibratePreference = findPreference(
                    getString(R.string.notification_vibration_pref_key));
            mSmsDisabledPrefKey = getString(R.string.sms_disabled_pref_key);
            mSmsDisabledPreference = findPreference(mSmsDisabledPrefKey);
            mSmsEnabledPrefKey = getString(R.string.sms_enabled_pref_key);
            mSmsEnabledPreference = findPreference(mSmsEnabledPrefKey);
            mIsSmsPreferenceClicked = false;
            mSettingNotificationChannelKey = getString(R.string.setting_notification_channel);
            mSettingNotificationChannel = findPreference(mSettingNotificationChannelKey);

            //Bkav QuangNDb chuyen xuong onResume de tranh loi khong cap nhat lai uri sound


            if (!DebugUtils.isDebugEnabled()) {
                final Preference debugCategory = findPreference(getString(
                        R.string.debug_pref_key));
                getPreferenceScreen().removePreference(debugCategory);
            }

            final PreferenceScreen advancedScreen = (PreferenceScreen) findPreference(
                    getString(R.string.advanced_pref_key));
            final boolean topLevel = getActivity().getIntent().getBooleanExtra(
                    UIIntents.UI_INTENT_EXTRA_TOP_LEVEL_SETTINGS, false);
            if (topLevel) {
                advancedScreen.setIntent(UIIntents.get()
                        .getAdvancedSettingsIntent(getPreferenceScreen().getContext()));
            } else {
                // Hide the Advanced settings screen if this is not top-level; these are shown at
                // the parent SettingsActivity.
                getPreferenceScreen().removePreference(advancedScreen);
            }
        }



        @Override
        public boolean onPreferenceTreeClick (PreferenceScreen preferenceScreen,
                Preference preference) {
            if (preference.getKey() ==  mSmsDisabledPrefKey ||
                    preference.getKey() == mSmsEnabledPrefKey) {
                mIsSmsPreferenceClicked = true;
            }
            return super.onPreferenceTreeClick(preferenceScreen, preference);
        }

        private void updateSoundSummary(final SharedPreferences sharedPreferences) {
            // The silent ringtone just returns an empty string
            String ringtoneName = mRingtonePreference.getContext().getString(
                    R.string.silent_ringtone);

            String ringtoneString = sharedPreferences.getString(mRingtonePreferenceKey, null);

            // Bootstrap the default setting in the preferences so that we have a valid selection
            // in the dialog the first time that the user opens it.
            if (ringtoneString == null) {
                ringtoneString = Settings.System.DEFAULT_NOTIFICATION_URI.toString();
                final SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(mRingtonePreferenceKey, ringtoneString);
                editor.apply();
            }

            if (!TextUtils.isEmpty(ringtoneString)) {
                final Uri ringtoneUri = Uri.parse(ringtoneString);
                final Ringtone tone = RingtoneManager.getRingtone(mRingtonePreference.getContext(),
                        ringtoneUri);

                if (tone != null) {
                    ringtoneName = tone.getTitle(mRingtonePreference.getContext());
                }
            }

            mRingtonePreference.setSummary(ringtoneName);
        }

        private void updateSmsEnabledPreferences() {
            if (!OsUtil.isAtLeastKLP()) {
                getPreferenceScreen().removePreference(mSmsDisabledPreference);
                getPreferenceScreen().removePreference(mSmsEnabledPreference);
            } else {
                final String defaultSmsAppLabel = getString(R.string.default_sms_app,
                        PhoneUtils.getDefault().getDefaultSmsAppLabel());
                boolean isSmsEnabledBeforeState;
                boolean isSmsEnabledCurrentState;
                if (PhoneUtils.getDefault().isDefaultSmsApp()) {
                    if (getPreferenceScreen().findPreference(mSmsEnabledPrefKey) == null) {
                        getPreferenceScreen().addPreference(mSmsEnabledPreference);
                        isSmsEnabledBeforeState = false;
                    } else {
                        isSmsEnabledBeforeState = true;
                    }
                    isSmsEnabledCurrentState = true;
                    getPreferenceScreen().removePreference(mSmsDisabledPreference);
                    mSmsEnabledPreference.setSummary(defaultSmsAppLabel);
                } else {
                    if (getPreferenceScreen().findPreference(mSmsDisabledPrefKey) == null) {
                        getPreferenceScreen().addPreference(mSmsDisabledPreference);
                        isSmsEnabledBeforeState = true;
                    } else {
                        isSmsEnabledBeforeState = false;
                    }
                    isSmsEnabledCurrentState = false;
                    getPreferenceScreen().removePreference(mSmsEnabledPreference);
                    mSmsDisabledPreference.setSummary(defaultSmsAppLabel);
                }
                updateNotificationsPreferences();
                // Bkav QuangNDb them ham endable, disable cho Report Delivery
                setEnableDeliveryReport(isSmsEnabledCurrentState);
            }
            mIsSmsPreferenceClicked = false;
        }

        private void updateNotificationsPreferences() {
            final boolean canNotify = !OsUtil.isAtLeastKLP()
                    || PhoneUtils.getDefault().isDefaultSmsApp();
//            mNotificationsEnabledPreference.setEnabled(canNotify);
            mSettingNotificationChannel.setEnabled(canNotify);
        }

        @Override
        public void onStart() {
            super.onStart();
            // We do this on start rather than on resume because the sound picker is in a
            // separate activity.
            getPreferenceScreen().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onResume() {
            super.onResume();
            updateSmsEnabledPreferences();
            updateNotificationsPreferences();
            //Bkav QuangNDb fix loi khong cap nhat uri sound
            final SharedPreferences prefs = getPreferenceScreen().getSharedPreferences();
//            updateSoundSummary(prefs);
        }

        @Override
        public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences,
                final String key) {
            if (key.equals(mNotificationsEnabledPreferenceKey)) {
                updateNotificationsPreferences();
            } else if (key.equals(mRingtonePreferenceKey)) {
//                updateSoundSummary(sharedPreferences);
            }
        }

        @Override
        public void onStop() {
            super.onStop();
            getPreferenceScreen().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
        }

        /**
         * ------------------------------------------------------BAKV----------------------------------------------------
         * Bkav QuangNDb: tach code doan get pref id de override lai o class con
         */
        protected int getPreferenceId() {
            return R.xml.preferences_application;
        }

        protected void setEnableDeliveryReport(boolean isSmsEnabledCurrentState) {
            // Bkav QuangNDb khong lam gi
        }
    }

    /**
     * -----------------------------------------------------------BAKV-----------------------------------------------------
     * Bkav QuangNDb tach code doan get fragment de override lai
     */
    protected Fragment getFragment() {
        return new ApplicationSettingsFragment();
    }

    /**
     * Bkav QuangNDb tach code doan press vao home button
     */
    protected void homeButtonPress() {
        NavUtils.navigateUpFromSameTask(this);
    }

    /**
     * Bkav QuangNDb them ham init mSlotId
     */
    protected void initSubId() {
        // Bkav QuangNDb khong lam gi
    }

    protected void setElevationActionbar() {
    }
}
