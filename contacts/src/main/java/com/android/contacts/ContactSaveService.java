/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.contacts;

import static android.Manifest.permission.WRITE_CONTACTS;
import android.app.Activity;
import android.app.IntentService;
import android.content.ContentProviderOperation;
import android.content.ContentProviderOperation.Builder;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.AggregationExceptions;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.Profile;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.RawContactsEntity;
import android.text.TextUtils;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionInfo;
import android.util.Log;
import android.widget.Toast;

import com.android.contacts.activities.ContactEditorBaseActivity;
import com.android.contacts.common.MoreContactUtils;
import com.android.contacts.common.SimContactsConstants;
import com.android.contacts.common.SimContactsOperation;
import com.android.contacts.common.model.ContactLoader;
import com.android.contacts.common.model.ValuesDelta;
import com.android.contacts.common.model.account.ExchangeAccountType;
import com.android.contacts.common.compat.CompatUtils;
import com.android.contacts.common.database.ContactUpdateUtils;
import com.android.contacts.common.model.AccountTypeManager;
import com.android.contacts.common.model.CPOWrapper;
import com.android.contacts.common.model.RawContactDelta;
import com.android.contacts.common.model.RawContactDeltaList;
import com.android.contacts.common.model.RawContactModifier;
import com.android.contacts.common.model.account.AccountWithDataSet;
import com.android.contacts.common.util.PermissionsUtil;
import com.android.contacts.compat.PinnedPositionsCompat;
import com.android.contacts.util.ContactPhotoUtils;
import com.android.contacts.util.VnCharacterUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.codeaurora.wrapper.UiccPhoneBookController_Wrapper;

/**
 * A service responsible for saving changes to the content provider.
 */
public class ContactSaveService extends IntentService {
    private static final String TAG = "ContactSaveService";

    /** Set to true in order to view logs on content provider operations */
    private static final boolean DEBUG = false;

    public static final String ACTION_NEW_RAW_CONTACT = "newRawContact";

    public static final String EXTRA_ACCOUNT_NAME = "accountName";
    public static final String EXTRA_ACCOUNT_TYPE = "accountType";
    public static final String EXTRA_DATA_SET = "dataSet";
    public static final String EXTRA_CONTENT_VALUES = "contentValues";
    public static final String EXTRA_CALLBACK_INTENT = "callbackIntent";

    public static final String ACTION_SAVE_CONTACT = "saveContact";
    public static final String EXTRA_CONTACT_STATE = "state";
    public static final String EXTRA_SAVE_MODE = "saveMode";
    public static final String EXTRA_SAVE_IS_PROFILE = "saveIsProfile";
    public static final String EXTRA_SAVE_SUCCEEDED = "saveSucceeded";
    public static final String EXTRA_UPDATED_PHOTOS = "updatedPhotos";
    public static final String SAVE_CONTACT_RESULT = "saveResult";

    public static final String ACTION_CREATE_GROUP = "createGroup";
    public static final String ACTION_RENAME_GROUP = "renameGroup";
    public static final String ACTION_DELETE_GROUP = "deleteGroup";
    public static final String ACTION_UPDATE_GROUP = "updateGroup";
    public static final String EXTRA_GROUP_ID = "groupId";
    public static final String EXTRA_GROUP_LABEL = "groupLabel";
    public static final String EXTRA_RAW_CONTACTS_TO_ADD = "rawContactsToAdd";
    public static final String EXTRA_RAW_CONTACTS_TO_REMOVE = "rawContactsToRemove";

    public static final String ACTION_SET_STARRED = "setStarred";
    public static final String ACTION_DELETE_CONTACT = "delete";
    public static final String ACTION_DELETE_MULTIPLE_CONTACTS = "deleteMultipleContacts";
    public static final String EXTRA_CONTACT_URI = "contactUri";
    public static final String EXTRA_CONTACT_IDS = "contactIds";
    public static final String EXTRA_STARRED_FLAG = "starred";

    public static final String ACTION_SET_SUPER_PRIMARY = "setSuperPrimary";
    public static final String ACTION_CLEAR_PRIMARY = "clearPrimary";
    public static final String EXTRA_DATA_ID = "dataId";

    public static final String ACTION_JOIN_CONTACTS = "joinContacts";
    public static final String ACTION_JOIN_SEVERAL_CONTACTS = "joinSeveralContacts";
    public static final String EXTRA_CONTACT_ID1 = "contactId1";
    public static final String EXTRA_CONTACT_ID2 = "contactId2";

    public static final String ACTION_SET_SEND_TO_VOICEMAIL = "sendToVoicemail";
    public static final String EXTRA_SEND_TO_VOICEMAIL_FLAG = "sendToVoicemailFlag";

    public static final String ACTION_SET_RINGTONE = "setRingtone";
    public static final String EXTRA_CUSTOM_RINGTONE = "customRingtone";
    //Bkav QuangNDb them param custom ring message
    public static final String EXTRA_CUSTOM_RINGTONE_SMS = "customRingtoneSMS";
    // Anhdts
    public static final String ACTION_SET_RINGTONE_SMS = "setRingtoneSms";

    private static final HashSet<String> ALLOWED_DATA_COLUMNS = Sets.newHashSet(
        Data.MIMETYPE,
        Data.IS_PRIMARY,
        Data.DATA1,
        Data.DATA2,
        Data.DATA3,
        Data.DATA4,
        Data.DATA5,
        Data.DATA6,
        Data.DATA7,
        Data.DATA8,
        Data.DATA9,
        Data.DATA10,
        Data.DATA11,
        Data.DATA12,
        Data.DATA13,
        Data.DATA14,
        Data.DATA15
    );

    private static final int PERSIST_TRIES = 3;

    public static final int RESULT_UNCHANGED = 0;
    public static final int RESULT_SUCCESS = 1;
    public static final int RESULT_FAILURE = 2;
    public static final int RESULT_NO_NUMBER_AND_EMAIL = 3;
    public static final int RESULT_SIM_FAILURE = 4;   //only for sim operation failure
    public static final int RESULT_EMAIL_FAILURE = 5; // only for sim email operation failure
    // only for sim failure of number or anr is too long
    public static final int RESULT_NUMBER_ANR_FAILURE = 6;
    public static final int RESULT_SIM_FULL_FAILURE = 7; // only for sim card is full
    public static final int RESULT_TAG_FAILURE = 8; // only for sim failure of name is too long
    public static final int RESULT_NUMBER_INVALID = 9; // only for sim failure of number is valid

    public static final int RESULT_MEMORY_FULL_FAILURE = 11; //for memory full exception
    public static final int RESULT_NUMBER_TYPE_FAILURE =12;  //only for sim failure of number TYPE

    private final int MAX_EMAIL_LENGTH = 40;
    private final int MAX_EN_LENGTH = 14;
    private final int MAX_CH_LENGTH = 6;

    public static final String CODE_SAVE_ERROR = "code_save_contact_error";
    public final static  int CODE_ERROR_VN_MAX_CHARACTER = 20;
    public final static int CODE_ERROR_MAX_CHARACTER = 21;

    private static SimContactsOperation mSimContactsOperation;
    private SubscriptionManager mSubscriptionManager;
    private static final int MAX_CONTACTS_PROVIDER_BATCH_SIZE = 499;

    public interface Listener {
        public void onServiceCompleted(Intent callbackIntent);
    }

    private static final CopyOnWriteArrayList<Listener> sListeners =
            new CopyOnWriteArrayList<Listener>();

    private Handler mMainHandler;

    public ContactSaveService() {
        super(TAG);
        setIntentRedelivery(true);
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSubscriptionManager = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
    }

    public static void registerListener(Listener listener) {
        if (!(listener instanceof Activity)) {
            throw new ClassCastException("Only activities can be registered to"
                    + " receive callback from " + ContactSaveService.class.getName());
        }
        sListeners.add(0, listener);
    }

    public static void unregisterListener(Listener listener) {
        sListeners.remove(listener);
    }

    /**
     * Returns true if the ContactSaveService was started successfully and false if an exception
     * was thrown and a Toast error message was displayed.
     */
    public static boolean startService(Context context, Intent intent, int saveMode) {
        try {
            context.startService(intent);
        } catch (Exception exception) {
            final int resId;
            switch (saveMode) {
                case ContactEditorBaseActivity.ContactEditor.SaveMode.SPLIT:
                    resId = R.string.contactUnlinkErrorToast;
                    break;
                case ContactEditorBaseActivity.ContactEditor.SaveMode.RELOAD:
                    resId = R.string.contactJoinErrorToast;
                    break;
                case ContactEditorBaseActivity.ContactEditor.SaveMode.CLOSE:
                    resId = R.string.contactSavedErrorToast;
                    break;
                default:
                    resId = R.string.contactGenericErrorToast;
            }
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    /**
     * Utility method that starts service and handles exception.
     */
    public static void startService(Context context, Intent intent) {
        try {
            context.startService(intent);
        } catch (Exception exception) {
            Toast.makeText(context, R.string.contactGenericErrorToast, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public Object getSystemService(String name) {
        Object service = super.getSystemService(name);
        if (service != null) {
            return service;
        }

        return getApplicationContext().getSystemService(name);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            Log.d(TAG, "onHandleIntent: could not handle null intent");
            return;
        }
        if (!PermissionsUtil.hasPermission(this, WRITE_CONTACTS)) {
            Log.w(TAG, "No WRITE_CONTACTS permission, unable to write to CP2");
            // TODO: add more specific error string such as "Turn on Contacts
            // permission to update your contacts"
            showToast(R.string.contactSavedErrorToast);
            return;
        }

        // Call an appropriate method. If we're sure it affects how incoming phone calls are
        // handled, then notify the fact to in-call screen.
        String action = intent.getAction();
        if (ACTION_NEW_RAW_CONTACT.equals(action)) {
            createRawContact(intent);
        } else if (ACTION_SAVE_CONTACT.equals(action)) {
            saveContact(intent);
        } else if (ACTION_CREATE_GROUP.equals(action)) {
            createGroup(intent);
        } else if (ACTION_RENAME_GROUP.equals(action)) {
            renameGroup(intent);
        } else if (ACTION_DELETE_GROUP.equals(action)) {
            deleteGroup(intent);
        } else if (ACTION_UPDATE_GROUP.equals(action)) {
            updateGroup(intent);
        } else if (ACTION_SET_STARRED.equals(action)) {
            setStarred(intent);
        } else if (ACTION_SET_SUPER_PRIMARY.equals(action)) {
            setSuperPrimary(intent);
        } else if (ACTION_CLEAR_PRIMARY.equals(action)) {
            clearPrimary(intent);
        } else if (ACTION_DELETE_MULTIPLE_CONTACTS.equals(action)) {
            deleteMultipleContacts(intent);
        } else if (ACTION_DELETE_CONTACT.equals(action)) {
            deleteContact(intent);
        } else if (ACTION_JOIN_CONTACTS.equals(action)) {
            joinContacts(intent);
        } else if (ACTION_JOIN_SEVERAL_CONTACTS.equals(action)) {
            joinSeveralContacts(intent);
        } else if (ACTION_SET_SEND_TO_VOICEMAIL.equals(action)) {
            setSendToVoicemail(intent);
        } else if (ACTION_SET_RINGTONE.equals(action)) {
            setRingtone(intent, true);
        } // Anhdts
        else if (ACTION_SET_RINGTONE_SMS.equals(action)) {
            setRingtone(intent, false);
        }
    }

    /**
     * Creates an intent that can be sent to this service to create a new raw contact
     * using data presented as a set of ContentValues.
     */
    public static Intent createNewRawContactIntent(Context context,
            ArrayList<ContentValues> values, AccountWithDataSet account,
            Class<? extends Activity> callbackActivity, String callbackAction) {
        Intent serviceIntent = new Intent(
                context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_NEW_RAW_CONTACT);
        if (account != null) {
            serviceIntent.putExtra(ContactSaveService.EXTRA_ACCOUNT_NAME, account.name);
            serviceIntent.putExtra(ContactSaveService.EXTRA_ACCOUNT_TYPE, account.type);
            serviceIntent.putExtra(ContactSaveService.EXTRA_DATA_SET, account.dataSet);
        }
        serviceIntent.putParcelableArrayListExtra(
                ContactSaveService.EXTRA_CONTENT_VALUES, values);

        // Callback intent will be invoked by the service once the new contact is
        // created.  The service will put the URI of the new contact as "data" on
        // the callback intent.
        Intent callbackIntent = new Intent(context, callbackActivity);
        callbackIntent.setAction(callbackAction);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CALLBACK_INTENT, callbackIntent);
        return serviceIntent;
    }

    private void createRawContact(Intent intent) {
        String accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME);
        String accountType = intent.getStringExtra(EXTRA_ACCOUNT_TYPE);
        String dataSet = intent.getStringExtra(EXTRA_DATA_SET);
        List<ContentValues> valueList = intent.getParcelableArrayListExtra(EXTRA_CONTENT_VALUES);
        Intent callbackIntent = intent.getParcelableExtra(EXTRA_CALLBACK_INTENT);

        ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();
        operations.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_NAME, accountName)
                .withValue(RawContacts.ACCOUNT_TYPE, accountType)
                .withValue(RawContacts.DATA_SET, dataSet)
                .build());

        int size = valueList.size();
        for (int i = 0; i < size; i++) {
            ContentValues values = valueList.get(i);
            values.keySet().retainAll(ALLOWED_DATA_COLUMNS);
            operations.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValues(values)
                    .build());
        }

        ContentResolver resolver = getContentResolver();
        ContentProviderResult[] results;
        try {
            results = resolver.applyBatch(ContactsContract.AUTHORITY, operations);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store new contact", e);
        }

        Uri rawContactUri = results[0].uri;
        callbackIntent.setData(RawContacts.getContactLookupUri(resolver, rawContactUri));

        deliverCallback(callbackIntent);
    }

    /**
     * Creates an intent that can be sent to this service to create a new raw contact
     * using data presented as a set of ContentValues.
     * This variant is more convenient to use when there is only one photo that can
     * possibly be updated, as in the Contact Details screen.
     * @param rawContactId identifies a writable raw-contact whose photo is to be updated.
     * @param updatedPhotoPath denotes a temporary file containing the contact's new photo.
     */
    public static Intent createSaveContactIntent(Context context, RawContactDeltaList state,
            String saveModeExtraKey, int saveMode, boolean isProfile,
            Class<? extends Activity> callbackActivity, String callbackAction, long rawContactId,
            Uri updatedPhotoPath) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(String.valueOf(rawContactId), updatedPhotoPath);
        return createSaveContactIntent(context, state, saveModeExtraKey, saveMode, isProfile,
                callbackActivity, callbackAction, bundle,
                /* joinContactIdExtraKey */ null, /* joinContactId */ null);
    }

    /**
     * Creates an intent that can be sent to this service to create a new raw contact
     * using data presented as a set of ContentValues.
     * This variant is used when multiple contacts' photos may be updated, as in the
     * Contact Editor.
     *
     * @param updatedPhotos maps each raw-contact's ID to the file-path of the new photo.
     * @param joinContactIdExtraKey the key used to pass the joinContactId in the callback intent.
     * @param joinContactId the raw contact ID to join to the contact after doing the save.
     */
    public static Intent createSaveContactIntent(Context context, RawContactDeltaList state,
            String saveModeExtraKey, int saveMode, boolean isProfile,
            Class<? extends Activity> callbackActivity, String callbackAction,
            Bundle updatedPhotos, String joinContactIdExtraKey, Long joinContactId) {
        Intent serviceIntent = new Intent(
                context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_SAVE_CONTACT);
        serviceIntent.putExtra(EXTRA_CONTACT_STATE, (Parcelable) state);
        serviceIntent.putExtra(EXTRA_SAVE_IS_PROFILE, isProfile);
        serviceIntent.putExtra(EXTRA_SAVE_MODE, saveMode);

        if (updatedPhotos != null) {
            serviceIntent.putExtra(EXTRA_UPDATED_PHOTOS, (Parcelable) updatedPhotos);
        }

        if (callbackActivity != null) {
            // Callback intent will be invoked by the service once the contact is
            // saved.  The service will put the URI of the new contact as "data" on
            // the callback intent.
            Intent callbackIntent = new Intent(context, callbackActivity);
            callbackIntent.putExtra(saveModeExtraKey, saveMode);
            if (joinContactIdExtraKey != null && joinContactId != null) {
                callbackIntent.putExtra(joinContactIdExtraKey, joinContactId);
            }
            callbackIntent.setAction(callbackAction);
            serviceIntent.putExtra(ContactSaveService.EXTRA_CALLBACK_INTENT, callbackIntent);
        }
        return serviceIntent;
    }

    private void saveContact(Intent intent) {
        RawContactDeltaList state = intent.getParcelableExtra(EXTRA_CONTACT_STATE);
        boolean isProfile = intent.getBooleanExtra(EXTRA_SAVE_IS_PROFILE, false);
        Bundle updatedPhotos = intent.getParcelableExtra(EXTRA_UPDATED_PHOTOS);
        long contactNotifyId = -1;
        if (state == null) {
            Log.e(TAG, "Invalid arguments for saveContact request");
            return;
        }

        int saveMode = intent.getIntExtra(EXTRA_SAVE_MODE, -1);
        // Trim any empty fields, and RawContacts, before persisting
        final AccountTypeManager accountTypes = AccountTypeManager.getInstance(this);
        RawContactModifier.trimEmpty(state, accountTypes);

        Uri lookupUri = null;

        final ContentResolver resolver = getContentResolver();

        boolean succeeded = false;

        // Keep track of the id of a newly raw-contact (if any... there can be at most one).
        long insertedRawContactId = -1;

        // Attempt to persist changes
        Integer result = RESULT_FAILURE;

        ArrayList<Long> rawContactsList = new ArrayList<Long>();
        boolean isCardOperation = false;
        for (int i=0; i < state.size(); i++) {
            final RawContactDelta entity = state.get(i);
            final String accountType = entity.getValues().getAsString(RawContacts.ACCOUNT_TYPE);
            final String accountName = entity.getValues().getAsString(RawContacts.ACCOUNT_NAME);
            rawContactsList.add(entity.getRawContactId());
            if (accountType != null
                    && ExchangeAccountType.isExchangeType(accountType))
                removeDisplayName(entity);
            final int subscription = MoreContactUtils.getSubscription(
                accountType, accountName);
            isCardOperation = (subscription != SubscriptionManager.INVALID_SUBSCRIPTION_ID) ?
                    true : false;
            if (isCardOperation) {
                result = doSaveToSimCard(entity, resolver, subscription);
                Log.d(TAG, "doSaveToSimCard result is  " + result);
            }
        }
        int tries = 0;
        while (tries++ < PERSIST_TRIES) {
            if (result == RESULT_SUCCESS || result == RESULT_FAILURE) {
            try {
                // Build operations and try applying
                final ArrayList<CPOWrapper> diffWrapper = state.buildDiffWrapper();

                final ArrayList<ContentProviderOperation> diff = Lists.newArrayList();

                for (CPOWrapper cpoWrapper : diffWrapper) {
                    diff.add(cpoWrapper.getOperation());
                }

                if (DEBUG) {
                    Log.v(TAG, "Content Provider Operations:");
                    for (ContentProviderOperation operation : diff) {
                        Log.v(TAG, operation.toString());
                    }
                }

                int numberProcessed = 0;
                boolean batchFailed = false;
                final ContentProviderResult[] results = new ContentProviderResult[diff.size()];
                while (numberProcessed < diff.size()) {
                    final int subsetCount = applyDiffSubset(diff, numberProcessed, results, resolver);
                    if (subsetCount == -1) {
                        Log.w(TAG, "Resolver.applyBatch failed in saveContacts");
                        batchFailed = true;
                        break;
                    } else {
                        numberProcessed += subsetCount;
                    }
                }

                if (batchFailed) {
                    // Retry save
                    continue;
                }

                final long rawContactId = getRawContactId(state, diffWrapper, results);
                if (rawContactId == -1) {
                    throw new IllegalStateException("Could not determine RawContact ID after save");
                }
                contactNotifyId = rawContactId;
                // We don't have to check to see if the value is still -1.  If we reach here,
                // the previous loop iteration didn't succeed, so any ID that we obtained is bogus.
                insertedRawContactId = getInsertedRawContactId(diffWrapper, results);
                if (isProfile) {
                    // Since the profile supports local raw contacts, which may have been completely
                    // removed if all information was removed, we need to do a special query to
                    // get the lookup URI for the profile contact (if it still exists).
                    Cursor c = resolver.query(Profile.CONTENT_URI,
                            new String[] {Contacts._ID, Contacts.LOOKUP_KEY},
                            null, null, null);
                    if (c == null) {
                        continue;
                    }
                    try {
                        if (c.moveToFirst()) {
                            final long contactId = c.getLong(0);
                            final String lookupKey = c.getString(1);
                            lookupUri = Contacts.getLookupUri(contactId, lookupKey);
                        }
                    } finally {
                        c.close();
                    }
                } else {
                    final Uri rawContactUri = ContentUris.withAppendedId(RawContacts.CONTENT_URI,
                                    rawContactId);
                    lookupUri = RawContacts.getContactLookupUri(resolver, rawContactUri);
                }
                if (lookupUri != null) {
                    Log.v(TAG, "Saved contact. New URI: " + lookupUri);
                    //Bkav QuangNDb 
                    String ringtoneUri = intent.getStringExtra(EXTRA_CUSTOM_RINGTONE);
                    String ringtoneSMSUri = intent.getStringExtra(EXTRA_CUSTOM_RINGTONE_SMS);
                    ContentValues values = new ContentValues();
                    values.put(Contacts.CUSTOM_RINGTONE, ringtoneUri);
                    values.put(ContactLoader.CUSTOM_RINGTONE_SMS, ringtoneSMSUri);
                    getContentResolver().update(lookupUri, values, null, null);
                }

                // We can change this back to false later, if we fail to save the contact photo.
                succeeded = true;
                break;

            } catch (RemoteException e) {
                // Something went wrong, bail without success
                Log.e(TAG, "Problem persisting user edits", e);
                break;

            } catch (IllegalArgumentException e) {
                // This is thrown by applyBatch on malformed requests
                Log.e(TAG, "Problem persisting user edits", e);
                showToast(R.string.contactSavedErrorToast);
                break;

            } catch (OperationApplicationException e) {
                // Version consistency failed, re-parent change and try again
                Log.w(TAG, "Version consistency failed, re-parenting: " + e.toString());
                final StringBuilder sb = new StringBuilder(RawContacts._ID + " IN(");
                boolean first = true;
                final int count = state.size();
                for (int i = 0; i < count; i++) {
                    Long rawContactId = state.getRawContactId(i);
                    if (rawContactId != null && rawContactId != -1) {
                        if (!first) {
                            sb.append(',');
                        }
                        sb.append(rawContactId);
                        first = false;
                    }
                }
                sb.append(")");

                if (first) {
                    throw new IllegalStateException(
                            "Version consistency failed for a new contact", e);
                }

                final RawContactDeltaList newState = RawContactDeltaList.fromQuery(
                        isProfile
                                ? RawContactsEntity.PROFILE_CONTENT_URI
                                : RawContactsEntity.CONTENT_URI,
                        resolver, sb.toString(), null, null);
                state = RawContactDeltaList.mergeAfter(newState, state);

                // Update the new state to use profile URIs if appropriate.
                if (isProfile) {
                    for (RawContactDelta delta : state) {
                        delta.setProfileQueryUri();
                        }
                    }
                }
            }
        }

        // Now save any updated photos.  We do this at the end to ensure that
        // the ContactProvider already knows about newly-created contacts.
        if (updatedPhotos != null) {
            for (String key : updatedPhotos.keySet()) {
                Uri photoUri = updatedPhotos.getParcelable(key);
                long rawContactId = Long.parseLong(key);

                // If the raw-contact ID is negative, we are saving a new raw-contact;
                // replace the bogus ID with the new one that we actually saved the contact at.
                if (rawContactId < 0) {
                    rawContactId = insertedRawContactId;
                }

                // If the save failed, insertedRawContactId will be -1
                if (rawContactId < 0 || !saveUpdatedPhoto(rawContactId, photoUri, saveMode)) {
                    contactNotifyId = -1;
                    succeeded = false;
                }
            }
        }

        Intent callbackIntent = intent.getParcelableExtra(EXTRA_CALLBACK_INTENT);
        if (callbackIntent != null) {
            if (succeeded) {
                // Mark the intent to indicate that the save was successful (even if the lookup URI
                // is now null).  For local contacts or the local profile, it's possible that the
                // save triggered removal of the contact, so no lookup URI would exist..
                callbackIntent.putExtra(EXTRA_SAVE_SUCCEEDED, true);
                //them ham refresh participant de cap nhat lai database local tin nhan
                refreshParticipant(contactNotifyId);
            }

            if(result == CODE_ERROR_MAX_CHARACTER){ // Bkav HuyNQN khong luu duoc tren SIM do dai hon 14 ki tu
                callbackIntent.putExtra(CODE_SAVE_ERROR, CODE_ERROR_MAX_CHARACTER);
            }else if(result == CODE_ERROR_VN_MAX_CHARACTER){// Bkav HuyNQN khong luu duoc tren SIM do luu tieng viet va dai qua ki tu cho phep
                callbackIntent.putExtra(CODE_SAVE_ERROR, CODE_ERROR_VN_MAX_CHARACTER);
            }

            callbackIntent.setData(lookupUri);
            deliverCallback(callbackIntent);
        }
    }


    private void removeDisplayName(RawContactDelta entity) {
        ArrayList<ValuesDelta> names = entity
                .getMimeEntries(StructuredName.CONTENT_ITEM_TYPE);
        if (names != null
                && names.get(0).containsKey(StructuredName.DISPLAY_NAME))
            names.get(0).putNull(StructuredName.DISPLAY_NAME);
    }

    private Integer doSaveToSimCard(RawContactDelta entity, ContentResolver resolver,
            int subscription) {

            boolean isInsert = entity.isContactInsert();
            Integer result = RESULT_SIM_FAILURE;
            mSimContactsOperation = new SimContactsOperation(this);

            ContentValues values = entity.buildSimDiff();
            String tag = null;
            String number = null;
            String anr = null;
            String email = null;

            if(entity.isContactInsert()){
                tag = values.getAsString(SimContactsConstants.STR_TAG);
                number = values.getAsString(SimContactsConstants.STR_NUMBER);
                anr = values.getAsString(SimContactsConstants.STR_ANRS);
                email = values.getAsString(SimContactsConstants.STR_EMAILS);
            } else {
                tag = values.getAsString(SimContactsConstants.STR_NEW_TAG);
                number = values.getAsString(SimContactsConstants.STR_NEW_NUMBER);
                anr = values.getAsString(SimContactsConstants.STR_NEW_ANRS);
                email = values.getAsString(SimContactsConstants.STR_NEW_EMAILS);
            }

            if (TextUtils.isEmpty(number) && TextUtils.isEmpty(anr) && TextUtils.isEmpty(email)) {
                return RESULT_NO_NUMBER_AND_EMAIL;
            }

            if (!TextUtils.isEmpty(number)) {
                if (number.length() > MoreContactUtils.MAX_LENGTH_NUMBER_IN_SIM) {
                    return RESULT_NUMBER_ANR_FAILURE;
                } else if (number.contains(SimContactsConstants.STR_ANRS)) {
                    return RESULT_NUMBER_TYPE_FAILURE;
                }
            }

            if (!TextUtils.isEmpty(anr)) {
                String[] anrs = anr.split(SimContactsConstants.ANR_SEP);
                if (anrs != null) {
                    if (anrs.length > MoreContactUtils
                            .getOneSimAnrCount(this, subscription)) {
                        return RESULT_NUMBER_TYPE_FAILURE;
                    }
                    for (String mAnr : anrs) {
                        if (mAnr.length() > MoreContactUtils.MAX_LENGTH_NUMBER_IN_SIM) {
                            return RESULT_NUMBER_ANR_FAILURE;
                        }
                    }
                }
            }

            if (!TextUtils.isEmpty(number) && TextUtils.isEmpty(PhoneNumberUtils
                    .stripSeparators(number))) {
                return RESULT_NUMBER_INVALID;
            }

            if (!TextUtils.isEmpty(email)) {
                String[] emails = email.split(SimContactsConstants.EMAIL_SEP);
                for (String mEmail : emails) {
                    if (mEmail != null && mEmail.length() > MAX_EMAIL_LENGTH) {
                        return RESULT_EMAIL_FAILURE;
                    }
                }
            }

        if (entity.isContactInsert()) {
            int count = 0;
            SubscriptionInfo subInfoRecord = null;
            try {
                subInfoRecord = mSubscriptionManager
                        .getActiveSubscriptionInfoForSimSlotIndex(subscription);
            } catch (SecurityException e) {
                Log.w(TAG, "SecurityException thrown, lack permission for"
                        + " getActiveSubscriptionInfoList", e);
            }
            if (subInfoRecord != null) {
                int[] adnCount = UiccPhoneBookController_Wrapper
                        .getAdnRecordsCapacityForSubscriber(subInfoRecord.getSubscriptionId());
                if(adnCount!=null)
                    count= adnCount[MoreContactUtils.ADN_USED_POS];
            }
            if (count >0 && count == MoreContactUtils.getAdnCount(this, subscription)) {
                return RESULT_SIM_FULL_FAILURE;
            }
        }

            if (isInsert) {
                Uri resultUri = mSimContactsOperation.insert(values,
                        subscription);
                if (resultUri != null) {
                    result = RESULT_SUCCESS;
                }else if(checkNameError(tag) == CODE_ERROR_MAX_CHARACTER){ // Bkav HuyNQN tra ve cac result loi ro rang
                    result = CODE_ERROR_MAX_CHARACTER; // Bkav HuyNQN loi do vuot qua 14 ky tu luu tren sim
                }else if(checkNameError(tag) == CODE_ERROR_VN_MAX_CHARACTER){
                    result = CODE_ERROR_VN_MAX_CHARACTER; // Bkav HuyNQN loi do su dung tieng viet va dai qua ki tu cho phep
                }
            } else {
                int resultInt = mSimContactsOperation.update(values,
                        subscription);
                if (resultInt == 1)
                    result = RESULT_SUCCESS;
            }
            return result;
        }

        // Bkav HuyNQN tra ve ma loi phu hop de thong bao voi nguoi dung mot cach ro rang
        private int checkNameError(String tagName){
            char[] charsTagName = tagName.toCharArray();
            for (char c :
                    charsTagName) {
                if (VnCharacterUtils.checkVnCharacter(c)){
                    return CODE_ERROR_VN_MAX_CHARACTER;
                }
            }
            return CODE_ERROR_MAX_CHARACTER;
        }

    /**
     * Splits "diff" into subsets based on "MAX_CONTACTS_PROVIDER_BATCH_SIZE", applies each of the
     * subsets, adds the returned array to "results".
     *
     * @return the size of the array, if not null; -1 when the array is null.
     */
    private int applyDiffSubset(ArrayList<ContentProviderOperation> diff, int offset,
            ContentProviderResult[] results, ContentResolver resolver)
            throws RemoteException, OperationApplicationException {
        final int subsetCount = Math.min(diff.size() - offset, MAX_CONTACTS_PROVIDER_BATCH_SIZE);
        final ArrayList<ContentProviderOperation> subset = new ArrayList<>();
        subset.addAll(diff.subList(offset, offset + subsetCount));
        final ContentProviderResult[] subsetResult = resolver.applyBatch(ContactsContract
                .AUTHORITY, subset);
        if (subsetResult == null || (offset + subsetResult.length) > results.length) {
            return -1;
        }
        for (ContentProviderResult c : subsetResult) {
            results[offset++] = c;
        }
        return subsetResult.length;
    }

    /**
     * Save updated photo for the specified raw-contact.
     * @return true for success, false for failure
     */
    private boolean saveUpdatedPhoto(long rawContactId, Uri photoUri, int saveMode) {
        final Uri outputUri = Uri.withAppendedPath(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId),
                RawContacts.DisplayPhoto.CONTENT_DIRECTORY);

        return ContactPhotoUtils.savePhotoFromUriToUri(this, photoUri, outputUri, (saveMode == 0));
    }

    /**
     * Find the ID of an existing or newly-inserted raw-contact.  If none exists, return -1.
     */
    private long getRawContactId(RawContactDeltaList state,
            final ArrayList<CPOWrapper> diffWrapper,
            final ContentProviderResult[] results) {
        long existingRawContactId = state.findRawContactId();
        if (existingRawContactId != -1) {
            return existingRawContactId;
        }

        return getInsertedRawContactId(diffWrapper, results);
    }

    /**
     * Find the ID of a newly-inserted raw-contact.  If none exists, return -1.
     */
    private long getInsertedRawContactId(
            final ArrayList<CPOWrapper> diffWrapper, final ContentProviderResult[] results) {
        if (results == null) {
            return -1;
        }
        final int diffSize = diffWrapper.size();
        final int numResults = results.length;
        for (int i = 0; i < diffSize && i < numResults; i++) {
            final CPOWrapper cpoWrapper = diffWrapper.get(i);
            final boolean isInsert = CompatUtils.isInsertCompat(cpoWrapper);
            if (isInsert && cpoWrapper.getOperation().getUri().getEncodedPath().contains(
                    RawContacts.CONTENT_URI.getEncodedPath())) {
                return ContentUris.parseId(results[i].uri);
            }
        }
        return -1;
    }

    /**
     * Creates an intent that can be sent to this service to create a new group as
     * well as add new members at the same time.
     *
     * @param context of the application
     * @param account in which the group should be created
     * @param label is the name of the group (cannot be null)
     * @param rawContactsToAdd is an array of raw contact IDs for contacts that
     *            should be added to the group
     * @param callbackActivity is the activity to send the callback intent to
     * @param callbackAction is the intent action for the callback intent
     */
    public static Intent createNewGroupIntent(Context context, AccountWithDataSet account,
            String label, long[] rawContactsToAdd, Class<? extends Activity> callbackActivity,
            String callbackAction) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_CREATE_GROUP);
        serviceIntent.putExtra(ContactSaveService.EXTRA_ACCOUNT_TYPE, account.type);
        serviceIntent.putExtra(ContactSaveService.EXTRA_ACCOUNT_NAME, account.name);
        serviceIntent.putExtra(ContactSaveService.EXTRA_DATA_SET, account.dataSet);
        serviceIntent.putExtra(ContactSaveService.EXTRA_GROUP_LABEL, label);
        serviceIntent.putExtra(ContactSaveService.EXTRA_RAW_CONTACTS_TO_ADD, rawContactsToAdd);

        // Callback intent will be invoked by the service once the new group is
        // created.
        Intent callbackIntent = new Intent(context, callbackActivity);
        callbackIntent.setAction(callbackAction);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CALLBACK_INTENT, callbackIntent);

        return serviceIntent;
    }

    private void createGroup(Intent intent) {
        String accountType = intent.getStringExtra(EXTRA_ACCOUNT_TYPE);
        String accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME);
        String dataSet = intent.getStringExtra(EXTRA_DATA_SET);
        String label = intent.getStringExtra(EXTRA_GROUP_LABEL);
        final long[] rawContactsToAdd = intent.getLongArrayExtra(EXTRA_RAW_CONTACTS_TO_ADD);

        ContentValues values = new ContentValues();
        values.put(Groups.ACCOUNT_TYPE, accountType);
        values.put(Groups.ACCOUNT_NAME, accountName);
        values.put(Groups.DATA_SET, dataSet);
        values.put(Groups.TITLE, label);

        final ContentResolver resolver = getContentResolver();

        // Create the new group
        final Uri groupUri = resolver.insert(Groups.CONTENT_URI, values);

        // If there's no URI, then the insertion failed. Abort early because group members can't be
        // added if the group doesn't exist
        if (groupUri == null) {
            Log.e(TAG, "Couldn't create group with label " + label);
            return;
        }

        // Add new group members
        addMembersToGroup(resolver, rawContactsToAdd, ContentUris.parseId(groupUri));

        // TODO: Move this into the contact editor where it belongs. This needs to be integrated
        // with the way other intent extras that are passed to the {@link ContactEditorActivity}.
        values.clear();
        values.put(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE);
        values.put(GroupMembership.GROUP_ROW_ID, ContentUris.parseId(groupUri));

        Intent callbackIntent = intent.getParcelableExtra(EXTRA_CALLBACK_INTENT);
        callbackIntent.setData(groupUri);
        // TODO: This can be taken out when the above TODO is addressed
        callbackIntent.putExtra(ContactsContract.Intents.Insert.DATA, Lists.newArrayList(values));
        deliverCallback(callbackIntent);
    }

    /**
     * Creates an intent that can be sent to this service to rename a group.
     */
    public static Intent createGroupRenameIntent(Context context, long groupId, String newLabel,
            Class<? extends Activity> callbackActivity, String callbackAction) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_RENAME_GROUP);
        serviceIntent.putExtra(ContactSaveService.EXTRA_GROUP_ID, groupId);
        serviceIntent.putExtra(ContactSaveService.EXTRA_GROUP_LABEL, newLabel);

        // Callback intent will be invoked by the service once the group is renamed.
        Intent callbackIntent = new Intent(context, callbackActivity);
        callbackIntent.setAction(callbackAction);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CALLBACK_INTENT, callbackIntent);

        return serviceIntent;
    }

    private void renameGroup(Intent intent) {
        long groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1);
        String label = intent.getStringExtra(EXTRA_GROUP_LABEL);

        if (groupId == -1) {
            Log.e(TAG, "Invalid arguments for renameGroup request");
            return;
        }

        ContentValues values = new ContentValues();
        values.put(Groups.TITLE, label);
        final Uri groupUri = ContentUris.withAppendedId(Groups.CONTENT_URI, groupId);
        getContentResolver().update(groupUri, values, null, null);

        Intent callbackIntent = intent.getParcelableExtra(EXTRA_CALLBACK_INTENT);
        callbackIntent.setData(groupUri);
        deliverCallback(callbackIntent);
    }

    /**
     * Creates an intent that can be sent to this service to delete a group.
     */
    public static Intent createGroupDeletionIntent(Context context, long groupId) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_DELETE_GROUP);
        serviceIntent.putExtra(ContactSaveService.EXTRA_GROUP_ID, groupId);
        return serviceIntent;
    }

    private void deleteGroup(Intent intent) {
        long groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1);
        if (groupId == -1) {
            Log.e(TAG, "Invalid arguments for deleteGroup request");
            return;
        }

        getContentResolver().delete(
                ContentUris.withAppendedId(Groups.CONTENT_URI, groupId), null, null);
    }

    /**
     * Creates an intent that can be sent to this service to rename a group as
     * well as add and remove members from the group.
     *
     * @param context of the application
     * @param groupId of the group that should be modified
     * @param newLabel is the updated name of the group (can be null if the name
     *            should not be updated)
     * @param rawContactsToAdd is an array of raw contact IDs for contacts that
     *            should be added to the group
     * @param rawContactsToRemove is an array of raw contact IDs for contacts
     *            that should be removed from the group
     * @param callbackActivity is the activity to send the callback intent to
     * @param callbackAction is the intent action for the callback intent
     */
    public static Intent createGroupUpdateIntent(Context context, long groupId, String newLabel,
            long[] rawContactsToAdd, long[] rawContactsToRemove,
            Class<? extends Activity> callbackActivity, String callbackAction) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_UPDATE_GROUP);
        serviceIntent.putExtra(ContactSaveService.EXTRA_GROUP_ID, groupId);
        serviceIntent.putExtra(ContactSaveService.EXTRA_GROUP_LABEL, newLabel);
        serviceIntent.putExtra(ContactSaveService.EXTRA_RAW_CONTACTS_TO_ADD, rawContactsToAdd);
        serviceIntent.putExtra(ContactSaveService.EXTRA_RAW_CONTACTS_TO_REMOVE,
                rawContactsToRemove);

        // Callback intent will be invoked by the service once the group is updated
        Intent callbackIntent = new Intent(context, callbackActivity);
        callbackIntent.setAction(callbackAction);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CALLBACK_INTENT, callbackIntent);

        return serviceIntent;
    }

    private void updateGroup(Intent intent) {
        long groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1);
        String label = intent.getStringExtra(EXTRA_GROUP_LABEL);
        long[] rawContactsToAdd = intent.getLongArrayExtra(EXTRA_RAW_CONTACTS_TO_ADD);
        long[] rawContactsToRemove = intent.getLongArrayExtra(EXTRA_RAW_CONTACTS_TO_REMOVE);

        if (groupId == -1) {
            Log.e(TAG, "Invalid arguments for updateGroup request");
            return;
        }

        final ContentResolver resolver = getContentResolver();
        final Uri groupUri = ContentUris.withAppendedId(Groups.CONTENT_URI, groupId);

        // Update group name if necessary
        if (label != null) {
            ContentValues values = new ContentValues();
            values.put(Groups.TITLE, label);
            resolver.update(groupUri, values, null, null);
        }

        // Add and remove members if necessary
        addMembersToGroup(resolver, rawContactsToAdd, groupId);
        removeMembersFromGroup(resolver, rawContactsToRemove, groupId);

        Intent callbackIntent = intent.getParcelableExtra(EXTRA_CALLBACK_INTENT);
        callbackIntent.setData(groupUri);
        deliverCallback(callbackIntent);
    }

    private static void addMembersToGroup(ContentResolver resolver, long[] rawContactsToAdd,
            long groupId) {
        if (rawContactsToAdd == null) {
            return;
        }
        for (long rawContactId : rawContactsToAdd) {
            try {
                final ArrayList<ContentProviderOperation> rawContactOperations =
                        new ArrayList<ContentProviderOperation>();

                // Build an assert operation to ensure the contact is not already in the group
                final ContentProviderOperation.Builder assertBuilder = ContentProviderOperation
                        .newAssertQuery(Data.CONTENT_URI);
                assertBuilder.withSelection(Data.RAW_CONTACT_ID + "=? AND " +
                        Data.MIMETYPE + "=? AND " + GroupMembership.GROUP_ROW_ID + "=?",
                        new String[] { String.valueOf(rawContactId),
                        GroupMembership.CONTENT_ITEM_TYPE, String.valueOf(groupId)});
                assertBuilder.withExpectedCount(0);
                rawContactOperations.add(assertBuilder.build());

                // Build an insert operation to add the contact to the group
                final ContentProviderOperation.Builder insertBuilder = ContentProviderOperation
                        .newInsert(Data.CONTENT_URI);
                insertBuilder.withValue(Data.RAW_CONTACT_ID, rawContactId);
                insertBuilder.withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE);
                insertBuilder.withValue(GroupMembership.GROUP_ROW_ID, groupId);
                rawContactOperations.add(insertBuilder.build());

                if (DEBUG) {
                    for (ContentProviderOperation operation : rawContactOperations) {
                        Log.v(TAG, operation.toString());
                    }
                }

                // Apply batch
                if (!rawContactOperations.isEmpty()) {
                    resolver.applyBatch(ContactsContract.AUTHORITY, rawContactOperations);
                }
            } catch (RemoteException e) {
                // Something went wrong, bail without success
                Log.e(TAG, "Problem persisting user edits for raw contact ID " +
                        String.valueOf(rawContactId), e);
            } catch (OperationApplicationException e) {
                // The assert could have failed because the contact is already in the group,
                // just continue to the next contact
                Log.w(TAG, "Assert failed in adding raw contact ID " +
                        String.valueOf(rawContactId) + ". Already exists in group " +
                        String.valueOf(groupId), e);
            }
        }
    }

    private static void removeMembersFromGroup(ContentResolver resolver, long[] rawContactsToRemove,
            long groupId) {
        if (rawContactsToRemove == null) {
            return;
        }
        for (long rawContactId : rawContactsToRemove) {
            // Apply the delete operation on the data row for the given raw contact's
            // membership in the given group. If no contact matches the provided selection, then
            // nothing will be done. Just continue to the next contact.
            resolver.delete(Data.CONTENT_URI, Data.RAW_CONTACT_ID + "=? AND " +
                    Data.MIMETYPE + "=? AND " + GroupMembership.GROUP_ROW_ID + "=?",
                    new String[] { String.valueOf(rawContactId),
                    GroupMembership.CONTENT_ITEM_TYPE, String.valueOf(groupId)});
        }
    }

    /**
     * Creates an intent that can be sent to this service to star or un-star a contact.
     */
    public static Intent createSetStarredIntent(Context context, Uri contactUri, boolean value) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_SET_STARRED);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CONTACT_URI, contactUri);
        serviceIntent.putExtra(ContactSaveService.EXTRA_STARRED_FLAG, value);

        return serviceIntent;
    }

    private void setStarred(Intent intent) {
        Uri contactUri = intent.getParcelableExtra(EXTRA_CONTACT_URI);
        boolean value = intent.getBooleanExtra(EXTRA_STARRED_FLAG, false);
        if (contactUri == null) {
            Log.e(TAG, "Invalid arguments for setStarred request");
            return;
        }

        final ContentValues values = new ContentValues(1);
        values.put(Contacts.STARRED, value);
        getContentResolver().update(contactUri, values, null, null);

        // Undemote the contact if necessary
        final Cursor c = getContentResolver().query(contactUri, new String[] {Contacts._ID},
                null, null, null);
        if (c == null) {
            return;
        }
        try {
            if (c.moveToFirst()) {
                final long id = c.getLong(0);

                // Don't bother undemoting if this contact is the user's profile.
                if (id < Profile.MIN_ID) {
                    PinnedPositionsCompat.undemote(getContentResolver(), id);
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * Creates an intent that can be sent to this service to set the redirect to voicemail.
     */
    public static Intent createSetSendToVoicemail(Context context, Uri contactUri,
            boolean value) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_SET_SEND_TO_VOICEMAIL);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CONTACT_URI, contactUri);
        serviceIntent.putExtra(ContactSaveService.EXTRA_SEND_TO_VOICEMAIL_FLAG, value);

        return serviceIntent;
    }

    private void setSendToVoicemail(Intent intent) {
        Uri contactUri = intent.getParcelableExtra(EXTRA_CONTACT_URI);
        boolean value = intent.getBooleanExtra(EXTRA_SEND_TO_VOICEMAIL_FLAG, false);
        if (contactUri == null) {
            Log.e(TAG, "Invalid arguments for setRedirectToVoicemail");
            return;
        }

        final ContentValues values = new ContentValues(1);
        values.put(Contacts.SEND_TO_VOICEMAIL, value);
        getContentResolver().update(contactUri, values, null, null);
    }

    /**
     * Creates an intent that can be sent to this service to save the contact's ringtone.
     */
    public static Intent createSetRingtone(Context context, Uri contactUri,
                                           String value, boolean isRingtone) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        // Anhdts them nhac chuong tin nhan
        serviceIntent.setAction(isRingtone ? ContactSaveService.ACTION_SET_RINGTONE :
                ContactSaveService.ACTION_SET_RINGTONE_SMS);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CONTACT_URI, contactUri);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CUSTOM_RINGTONE, value);

        return serviceIntent;
    }

    private void setRingtone(Intent intent, boolean isCallRingtone) {
        Uri contactUri = intent.getParcelableExtra(EXTRA_CONTACT_URI);
        String value = intent.getStringExtra(EXTRA_CUSTOM_RINGTONE);
        if (contactUri == null) {
            Log.e(TAG, "Invalid arguments for setRingtone");
            return;
        }
        ContentValues values = new ContentValues(1);
        // Anhdts
        values.put(isCallRingtone ? Contacts.CUSTOM_RINGTONE : ContactLoader.CUSTOM_RINGTONE_SMS, value);
        getContentResolver().update(contactUri, values, null, null);
    }

    /**
     * Creates an intent that sets the selected data item as super primary (default)
     */
    public static Intent createSetSuperPrimaryIntent(Context context, long dataId) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_SET_SUPER_PRIMARY);
        serviceIntent.putExtra(ContactSaveService.EXTRA_DATA_ID, dataId);
        return serviceIntent;
    }

    private void setSuperPrimary(Intent intent) {
        long dataId = intent.getLongExtra(EXTRA_DATA_ID, -1);
        if (dataId == -1) {
            Log.e(TAG, "Invalid arguments for setSuperPrimary request");
            return;
        }

        ContactUpdateUtils.setSuperPrimary(this, dataId);
    }

    /**
     * Creates an intent that clears the primary flag of all data items that belong to the same
     * raw_contact as the given data item. Will only clear, if the data item was primary before
     * this call
     */
    public static Intent createClearPrimaryIntent(Context context, long dataId) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_CLEAR_PRIMARY);
        serviceIntent.putExtra(ContactSaveService.EXTRA_DATA_ID, dataId);
        return serviceIntent;
    }

    private void clearPrimary(Intent intent) {
        long dataId = intent.getLongExtra(EXTRA_DATA_ID, -1);
        if (dataId == -1) {
            Log.e(TAG, "Invalid arguments for clearPrimary request");
            return;
        }

        // Update the primary values in the data record.
        ContentValues values = new ContentValues(1);
        values.put(Data.IS_SUPER_PRIMARY, 0);
        values.put(Data.IS_PRIMARY, 0);

        getContentResolver().update(ContentUris.withAppendedId(Data.CONTENT_URI, dataId),
                values, null, null);
    }

    /**
     * Creates an intent that can be sent to this service to delete a contact.
     */
    public static Intent createDeleteContactIntent(Context context, Uri contactUri) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_DELETE_CONTACT);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CONTACT_URI, contactUri);
        return serviceIntent;
    }

    /**
     * Creates an intent that can be sent to this service to delete multiple contacts.
     */
    public static Intent createDeleteMultipleContactsIntent(Context context,
            long[] contactIds) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_DELETE_MULTIPLE_CONTACTS);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CONTACT_IDS, contactIds);
        return serviceIntent;
    }

    private void deleteContact(Intent intent) {
        Uri contactUri = intent.getParcelableExtra(EXTRA_CONTACT_URI);
        mSimContactsOperation = new SimContactsOperation(this);
        if (contactUri == null) {
            Log.e(TAG, "Invalid arguments for deleteContact request");
            return;
        }

        final List<String> segments = contactUri.getPathSegments();
        // Contains an Id.
        final long uriContactId = Long.parseLong(segments.get(3));
        int subscription = mSimContactsOperation
                .getSimSubscription(uriContactId);
        if (subscription != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            ContentValues values = mSimContactsOperation
                    .getSimAccountValues(uriContactId);
            int result = mSimContactsOperation.delete(values, subscription);
            if (result == RESULT_SUCCESS) {
                getContentResolver().delete(contactUri, null, null);
            }
        } else {
            getContentResolver().delete(contactUri, null, null);
        }
    }

    private void deleteMultipleContacts(Intent intent) {
        final long[] contactIds = intent.getLongArrayExtra(EXTRA_CONTACT_IDS);
        if (contactIds == null) {
            Log.e(TAG, "Invalid arguments for deleteMultipleContacts request");
            return;
        }
        for (long contactId : contactIds) {
            final Uri contactUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId);
            getContentResolver().delete(contactUri, null, null);
        }
        final String deleteToastMessage = getResources().getQuantityString(R.plurals
                .contacts_deleted_toast, contactIds.length);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ContactSaveService.this, deleteToastMessage, Toast.LENGTH_LONG)
                        .show();
            }
        });
    }

    /**
     * Creates an intent that can be sent to this service to join two contacts.
     * The resulting contact uses the name from {@param contactId1} if possible.
     */
    public static Intent createJoinContactsIntent(Context context, long contactId1,
            long contactId2, Class<? extends Activity> callbackActivity, String callbackAction) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_JOIN_CONTACTS);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CONTACT_ID1, contactId1);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CONTACT_ID2, contactId2);

        // Callback intent will be invoked by the service once the contacts are joined.
        Intent callbackIntent = new Intent(context, callbackActivity);
        callbackIntent.setAction(callbackAction);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CALLBACK_INTENT, callbackIntent);

        return serviceIntent;
    }

    /**
     * Creates an intent to join all raw contacts inside {@param contactIds}'s contacts.
     * No special attention is paid to where the resulting contact's name is taken from.
     */
    public static Intent createJoinSeveralContactsIntent(Context context, long[] contactIds) {
        Intent serviceIntent = new Intent(context, ContactSaveService.class);
        serviceIntent.setAction(ContactSaveService.ACTION_JOIN_SEVERAL_CONTACTS);
        serviceIntent.putExtra(ContactSaveService.EXTRA_CONTACT_IDS, contactIds);
        return serviceIntent;
    }


    private interface JoinContactQuery {
        String[] PROJECTION = {
                RawContacts._ID,
                RawContacts.CONTACT_ID,
                RawContacts.DISPLAY_NAME_SOURCE,
        };

        int _ID = 0;
        int CONTACT_ID = 1;
        int DISPLAY_NAME_SOURCE = 2;
    }

    private interface ContactEntityQuery {
        String[] PROJECTION = {
                Contacts.Entity.DATA_ID,
                Contacts.Entity.CONTACT_ID,
                Contacts.Entity.IS_SUPER_PRIMARY,
        };
        String SELECTION = Data.MIMETYPE + " = '" + StructuredName.CONTENT_ITEM_TYPE + "'" +
                " AND " + StructuredName.DISPLAY_NAME + "=" + Contacts.DISPLAY_NAME +
                " AND " + StructuredName.DISPLAY_NAME + " IS NOT NULL " +
                " AND " + StructuredName.DISPLAY_NAME + " != '' ";

        int DATA_ID = 0;
        int CONTACT_ID = 1;
        int IS_SUPER_PRIMARY = 2;
    }

    private void joinSeveralContacts(Intent intent) {
        final long[] contactIds = intent.getLongArrayExtra(EXTRA_CONTACT_IDS);

        // Load raw contact IDs for all contacts involved.
        long rawContactIds[] = getRawContactIdsForAggregation(contactIds);
        if (rawContactIds == null) {
            Log.e(TAG, "Invalid arguments for joinSeveralContacts request");
            return;
        }

        // For each pair of raw contacts, insert an aggregation exception
        final ContentResolver resolver = getContentResolver();
        // The maximum number of operations per batch (aka yield point) is 500. See b/22480225
        final int batchSize = MAX_CONTACTS_PROVIDER_BATCH_SIZE;
        final ArrayList<ContentProviderOperation> operations = new ArrayList<>(batchSize);
        for (int i = 0; i < rawContactIds.length; i++) {
            for (int j = 0; j < rawContactIds.length; j++) {
                if (i != j) {
                    buildJoinContactDiff(operations, rawContactIds[i], rawContactIds[j]);
                }
                // Before we get to 500 we need to flush the operations list
                if (operations.size() > 0 && operations.size() % batchSize == 0) {
                    if (!applyJoinOperations(resolver, operations)) {
                        return;
                    }
                    operations.clear();
                }
            }
        }
        if (operations.size() > 0 && !applyJoinOperations(resolver, operations)) {
            return;
        }
        showToast(R.string.contactsJoinedMessage);
    }

    /** Returns true if the batch was successfully applied and false otherwise. */
    private boolean applyJoinOperations(ContentResolver resolver,
            ArrayList<ContentProviderOperation> operations) {
        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, operations);
            return true;
        } catch (RemoteException | OperationApplicationException e) {
            Log.e(TAG, "Failed to apply aggregation exception batch", e);
            showToast(R.string.contactSavedErrorToast);
            return false;
        }
    }


    private void joinContacts(Intent intent) {
        long contactId1 = intent.getLongExtra(EXTRA_CONTACT_ID1, -1);
        long contactId2 = intent.getLongExtra(EXTRA_CONTACT_ID2, -1);

        // Load raw contact IDs for all raw contacts involved - currently edited and selected
        // in the join UIs.
        long rawContactIds[] = getRawContactIdsForAggregation(contactId1, contactId2);
        if (rawContactIds == null) {
            Log.e(TAG, "Invalid arguments for joinContacts request");
            return;
        }

        ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();

        // For each pair of raw contacts, insert an aggregation exception
        for (int i = 0; i < rawContactIds.length; i++) {
            for (int j = 0; j < rawContactIds.length; j++) {
                if (i != j) {
                    buildJoinContactDiff(operations, rawContactIds[i], rawContactIds[j]);
                }
            }
        }

        final ContentResolver resolver = getContentResolver();

        // Use the name for contactId1 as the name for the newly aggregated contact.
        final Uri contactId1Uri = ContentUris.withAppendedId(
                Contacts.CONTENT_URI, contactId1);
        final Uri entityUri = Uri.withAppendedPath(
                contactId1Uri, Contacts.Entity.CONTENT_DIRECTORY);
        Cursor c = resolver.query(entityUri,
                ContactEntityQuery.PROJECTION, ContactEntityQuery.SELECTION, null, null);
        if (c == null) {
            Log.e(TAG, "Unable to open Contacts DB cursor");
            showToast(R.string.contactSavedErrorToast);
            return;
        }
        long dataIdToAddSuperPrimary = -1;
        try {
            if (c.moveToFirst()) {
                dataIdToAddSuperPrimary = c.getLong(ContactEntityQuery.DATA_ID);
            }
        } finally {
            c.close();
        }

        // Mark the name from contactId1 IS_SUPER_PRIMARY to make sure that the contact
        // display name does not change as a result of the join.
        if (dataIdToAddSuperPrimary != -1) {
            Builder builder = ContentProviderOperation.newUpdate(
                    ContentUris.withAppendedId(Data.CONTENT_URI, dataIdToAddSuperPrimary));
            builder.withValue(Data.IS_SUPER_PRIMARY, 1);
            builder.withValue(Data.IS_PRIMARY, 1);
            operations.add(builder.build());
        }

        boolean success = false;
        // Apply all aggregation exceptions as one batch
        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, operations);
            showToast(R.string.contactsJoinedMessage);
            success = true;
        } catch (RemoteException | OperationApplicationException e) {
            Log.e(TAG, "Failed to apply aggregation exception batch", e);
            showToast(R.string.contactSavedErrorToast);
        }

        Intent callbackIntent = intent.getParcelableExtra(EXTRA_CALLBACK_INTENT);
        if (success) {
            Uri uri = RawContacts.getContactLookupUri(resolver,
                    ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactIds[0]));
            callbackIntent.setData(uri);
        }
        deliverCallback(callbackIntent);
    }

    private long[] getRawContactIdsForAggregation(long[] contactIds) {
        if (contactIds == null) {
            return null;
        }

        final ContentResolver resolver = getContentResolver();
        long rawContactIds[];

        final StringBuilder queryBuilder = new StringBuilder();
        final String stringContactIds[] = new String[contactIds.length];
        queryBuilder.append(RawContacts.CONTACT_ID + " in (");
        for (int i = 0; i < contactIds.length; i++) {
            stringContactIds[i] = String.valueOf(contactIds[i]);
            queryBuilder.append(stringContactIds[i]);
            if (contactIds[i] == -1) {
                return null;
            }
            if (i == contactIds.length -1) {
                queryBuilder.append(")");
                break;
            }
            queryBuilder.append(", ");
        }

        final Cursor c = resolver.query(RawContacts.CONTENT_URI,
                JoinContactQuery.PROJECTION,
                queryBuilder.toString(),
                null, null);
        if (c == null) {
            Log.e(TAG, "Unable to open Contacts DB cursor");
            showToast(R.string.contactSavedErrorToast);
            return null;
        }
        try {
            if (c.getCount() < 2) {
                Log.e(TAG, "Not enough raw contacts to aggregate together.");
                return null;
            }
            rawContactIds = new long[c.getCount()];
            for (int i = 0; i < rawContactIds.length; i++) {
                c.moveToPosition(i);
                long rawContactId = c.getLong(JoinContactQuery._ID);
                rawContactIds[i] = rawContactId;
            }
        } finally {
            c.close();
        }
        return rawContactIds;
    }

    private long[] getRawContactIdsForAggregation(long contactId1, long contactId2) {
        return getRawContactIdsForAggregation(new long[] {contactId1, contactId2});
    }

    /**
     * Construct a {@link AggregationExceptions#TYPE_KEEP_TOGETHER} ContentProviderOperation.
     */
    private void buildJoinContactDiff(ArrayList<ContentProviderOperation> operations,
            long rawContactId1, long rawContactId2) {
        Builder builder =
                ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI);
        builder.withValue(AggregationExceptions.TYPE, AggregationExceptions.TYPE_KEEP_TOGETHER);
        builder.withValue(AggregationExceptions.RAW_CONTACT_ID1, rawContactId1);
        builder.withValue(AggregationExceptions.RAW_CONTACT_ID2, rawContactId2);
        operations.add(builder.build());
    }

    /**
     * Shows a toast on the UI thread.
     */
    private void showToast(final int message) {
        mMainHandler.post(new Runnable() {

            @Override
            public void run() {
                Toast.makeText(ContactSaveService.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void deliverCallback(final Intent callbackIntent) {
        mMainHandler.post(new Runnable() {

            @Override
            public void run() {
                deliverCallbackOnUiThread(callbackIntent);
            }
        });
    }

    void deliverCallbackOnUiThread(final Intent callbackIntent) {
        // TODO: this assumes that if there are multiple instances of the same
        // activity registered, the last one registered is the one waiting for
        // the callback. Validity of this assumption needs to be verified.
        for (Listener listener : sListeners) {
            if (callbackIntent.getComponent().equals(
                    ((Activity) listener).getIntent().getComponent())) {
                listener.onServiceCompleted(callbackIntent);
                return;
            }
        }
    }


    //====================================================================BKAV========================================================
    /**Bkav QuangNDb cap nhat lai participant khi contact co su thay doi*/
    protected void refreshParticipant(long contactId) {

    }

}
