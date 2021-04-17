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
package com.android.messaging.ui.conversation;

import android.app.FragmentManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.support.v7.app.ActionBar;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.Editable;
import android.text.Html;
import android.text.InputFilter;
import android.text.InputFilter.LengthFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.binding.Binding;
import com.android.messaging.datamodel.binding.BindingBase;
import com.android.messaging.datamodel.binding.ImmutableBindingRef;
import com.android.messaging.datamodel.data.ConversationData;
import com.android.messaging.datamodel.data.ConversationData.ConversationDataListener;
import com.android.messaging.datamodel.data.ConversationData.SimpleConversationDataListener;
import com.android.messaging.datamodel.data.DraftMessageData;
import com.android.messaging.datamodel.data.DraftMessageData.CheckDraftForSendTask;
import com.android.messaging.datamodel.data.DraftMessageData.CheckDraftTaskCallback;
import com.android.messaging.datamodel.data.DraftMessageData.DraftMessageDataListener;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.datamodel.data.PendingAttachmentData;
import com.android.messaging.datamodel.data.SubscriptionListData;
import com.android.messaging.datamodel.data.SubscriptionListData.SubscriptionListEntry;
import com.android.messaging.sms.MmsConfig;
import com.android.messaging.ui.AttachmentPreview;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.PlainTextEditText;
import com.android.messaging.ui.conversation.ConversationInputManager.ConversationInputSink;
import com.android.messaging.util.AccessibilityUtil;
import com.android.messaging.util.Assert;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.MediaUtil;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.UiUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This view contains the UI required to generate and send messages.
 */
public class ComposeMessageView extends LinearLayout
        implements TextView.OnEditorActionListener, DraftMessageDataListener, TextWatcher,
        ConversationInputSink {

    public interface IComposeMessageViewHost extends
            DraftMessageData.DraftMessageSubscriptionDataProvider {
        void sendMessage(MessageData message);
        void onComposeEditTextFocused();
        void onAttachmentsCleared();
        void onAttachmentsChanged(final boolean haveAttachments);
        void displayPhoto(Uri photoUri, Rect imageBounds, boolean isDraft);
        void promptForSelfPhoneNumber();
        boolean isReadyForAction();
        void warnOfMissingActionConditions(final boolean sending,
                final Runnable commandToRunAfterActionConditionResolved);
        void warnOfExceedingMessageLimit(final boolean showAttachmentChooser,
                boolean tooManyVideos);
        void notifyOfAttachmentLoadFailed();
        void showAttachmentChooser();
        boolean shouldShowSubjectEditor();
        boolean shouldHideAttachmentsWhenSimSelectorShown();
        Uri getSelfSendButtonIconUri();
        int overrideCounterColor();
        int getAttachmentsClearedFlags();
        // Bkav QuangNDb them 1 method bat su kien san sang gui tin nhan
        void onReadyMessage();
        boolean isAlwaysAskSim();//Bkav QuangNDb them check xem nguoi dung co dat che do luon hoi sim khi gui tin nhan khong
        boolean isFinishLoadDraf();
        FragmentManager getFragmentManager();//Bkav QuangNDb get FragmentManager tu host
        void showQuickResponse();
        boolean isReadySendMessage();//Bkav QuangNDb check xem da san sang de gui tin tu client chua
        void sendQuickResponse(Object data);//Bkav QuangNDb send quickresponse
        void keyboardShow();
        void emojiClick();
    }

    protected ViewGroup mParentView;// bien view cha


    public static final int CODEPOINTS_REMAINING_BEFORE_COUNTER_SHOWN = 10;

    // There is no draft and there is no need for the SIM selector
    private static final int SEND_WIDGET_MODE_SELF_AVATAR = 1;
    // There is no draft but we need to show the SIM selector
    private static final int SEND_WIDGET_MODE_SIM_SELECTOR = 2;
    // There is a draft
    protected static final int SEND_WIDGET_MODE_SEND_BUTTON = 3;

    protected PlainTextEditText mComposeEditText;
    protected PlainTextEditText mComposeSubjectText;
    protected TextView mCharCounter;
    protected TextView mMmsIndicator;
    protected SimIconView mSelfSendIcon;
    protected ImageButton mSendButton;
    private View mSubjectView;
    private ImageButton mDeleteSubjectButton;
    private AttachmentPreview mAttachmentPreview;
    protected ImageButton mAttachMediaButton;

    protected final Binding<DraftMessageData> mBinding;
    protected IComposeMessageViewHost mHost;
    protected final Context mOriginalContext;
    private int mSendWidgetMode = SEND_WIDGET_MODE_SELF_AVATAR;

    // Shared data model object binding from the conversation.
    protected ImmutableBindingRef<ConversationData> mConversationDataModel;

    // Centrally manages all the mutual exclusive UI components accepting user input, i.e.
    // media picker, IME keyboard and SIM selector.
    protected ConversationInputManager mInputManager;

    //Bkav QuangNDb bien check multisim
    protected boolean mIsMultiSim = false;
    //HienDTk: check cho hien ban phim len khong
    protected boolean mIsShowKeyboad = false;

    //Bkav QuangNDb them list sub entry de xac dinh sim khi gui tin nhan
    protected List<SubscriptionListData.SubscriptionListEntry> mListSubEntry = new ArrayList<>();

    private final ConversationDataListener mDataListener = new SimpleConversationDataListener() {
        @Override
        public void onConversationMetadataUpdated(ConversationData data) {
            mConversationDataModel.ensureBound(data);
            updateVisualsOnDraftChanged(null);
        }

        @Override
        public void onConversationParticipantDataLoaded(ConversationData data) {
            mConversationDataModel.ensureBound(data);
            updateVisualsOnDraftChanged(null);
        }

        @Override
        public void onSubscriptionListDataLoaded(ConversationData data) {
            mConversationDataModel.ensureBound(data);
            //Bkav QuangNDb update list sub entry khi co su thay doi ve sim
            mListSubEntry = data.getSubscriptionListData().getActiveSubscriptionEntriesExcludingDefault();
            updateOnSelfSubscriptionChange();
            updateVisualsOnDraftChanged(null);
        }
    };

    public ComposeMessageView(final Context context, final AttributeSet attrs) {
        super(new ContextThemeWrapper(context, R.style.ColorAccentBlueOverrideStyle), attrs);
        mOriginalContext = context;
        mBinding = BindingBase.createBinding(this);
        //Bkav QuangNDb check multisim
        checkMultiSim();
    }

    protected String mFirstDraf = "";
    private String mConversationId = "-1";
    /**
     * Host calls this to bind view to DraftMessageData objectbws
     */
    public void bind(final DraftMessageData data, final IComposeMessageViewHost host) {
        mHost = host;
        mBinding.bind(data);
        data.addListener(this);
        data.setSubscriptionDataProvider(host);
        final int counterColor = mHost.overrideCounterColor();
        if (counterColor != -1) {
            mCharCounter.setTextColor(counterColor);
        }
    }

    /**
     * Host calls this to unbind view
     */
    public void unbind() {
        mBinding.unbind();
        mHost = null;
        mInputManager.onDetach();
    }

    @Override
    protected void onFinishInflate() {
        initComposeEditText();//Bkav QuangNDb tach code doan khoi tao compose edittext
        mComposeEditText.setOnEditorActionListener(this);
        mComposeEditText.addTextChangedListener(this);
        mComposeEditText.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(final View v, final boolean hasFocus) {
                if (v == mComposeEditText && hasFocus) {
                    // Bkav TienNAb: hien thi ban phim va dong giao dien mediapicker
                    showKeyboard();
                    mHost.onComposeEditTextFocused();
                }
            }
        });
        mComposeEditText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                showKeyboard(); // Bkav HuyNQN thuc hien mo ban phim len va dong lai mediapicker
                if (mHost.shouldHideAttachmentsWhenSimSelectorShown()) {
                    hideSimSelector();
                }
            }
        });

        // onFinishInflate() is called before self is loaded from db. We set the default text
        // limit here, and apply the real limit later in updateOnSelfSubscriptionChange().
        mComposeEditText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(ParticipantData.DEFAULT_SELF_SUB_ID)
                        .getMaxTextLimit()) });

        initSimIconView();
        getSimIconView().setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                clickSelfSendIcon();
            }
        });
        onLongClickSelfSendButton();

        mComposeSubjectText = (PlainTextEditText) findViewById(
                R.id.compose_subject_text);
        // We need the listener to change the avatar to the send button when the user starts
        // typing a subject without a message.
        mComposeSubjectText.addTextChangedListener(this);
        mComposeSubjectText.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (v == mComposeSubjectText && hasFocus) {
                    hideMediaPicker();
                }
            }
        });
        // onFinishInflate() is called before self is loaded from db. We set the default text
        // limit here, and apply the real limit later in updateOnSelfSubscriptionChange().
        mComposeSubjectText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(ParticipantData.DEFAULT_SELF_SUB_ID)
                        .getMaxSubjectLength())});

        mDeleteSubjectButton = (ImageButton) findViewById(R.id.delete_subject_button);
        mDeleteSubjectButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View clickView) {
                hideSubjectEditor();
                mComposeSubjectText.setText(null);
                mBinding.getData().setMessageSubject(null);
            }
        });

        mSubjectView = findViewById(R.id.subject_view);

        mSendButton = (ImageButton) findViewById(R.id.send_message_button);
        mSendButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View clickView) {
                clickSendButton();
            }
        });
        onLongClickSendButton();
        mSendButton.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override
            public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
                super.onPopulateAccessibilityEvent(host, event);
                // When the send button is long clicked, we want TalkBack to announce the real
                // action (select SIM or edit subject), as opposed to "long press send button."
                if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                    event.getText().clear();
                    event.getText().add(getResources()
                            .getText(shouldShowSimSelector(mConversationDataModel.getData()) ?
                            R.string.send_button_long_click_description_with_sim_selector :
                                R.string.send_button_long_click_description_no_sim_selector));
                    // Make this an announcement so TalkBack will read our custom message.
                    event.setEventType(AccessibilityEvent.TYPE_ANNOUNCEMENT);
                }
            }
        });

        mAttachMediaButton =
                (ImageButton) findViewById(R.id.attach_media_button);
        mAttachMediaButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View clickView) {
                mIsShowKeyboad = true;
                // Showing the media picker is treated as starting to compose the message.
                // Bkav QuangNDb them ham hide keyboard khi hien media picker
                boolean isShow = mInputManager.getMediaInput().isOpen();
                showOrHideMediaPicker(isShow);

            }
        });

        //Bkav QuangNDb them xu ly longclick attach button
        longClickAttachButton();

        // Bkav QuangNDb tach code doan set Attachment view
        mAttachmentPreview = getAttachmentView();
        mAttachmentPreview.setComposeMessageView(this);

        mCharCounter = (TextView) findViewById(R.id.char_counter);
        mMmsIndicator = (TextView) findViewById(R.id.mms_indicator);
    }

    /**Bkav QuangNDb khoi tao simIconVIew*/
    protected void initSimIconView() {
        mSelfSendIcon = (SimIconView) findViewById(R.id.self_send_icon);
    }



    protected void hideAttachmentsWhenShowingSims(final boolean simPickerVisible) {
        if (!mHost.shouldHideAttachmentsWhenSimSelectorShown()) {
            return;
        }
        final boolean haveAttachments = mBinding.getData().hasAttachments();
        if (simPickerVisible && haveAttachments) {
            mHost.onAttachmentsChanged(false);
            mAttachmentPreview.hideAttachmentPreview();
        } else {
            mHost.onAttachmentsChanged(haveAttachments);
            mAttachmentPreview.onAttachmentsChanged(mBinding.getData());
        }
    }


    public void setInputManager(final ConversationInputManager inputManager) {
        mInputManager = inputManager;
    }

    public void setConversationDataModel(final ImmutableBindingRef<ConversationData> refDataModel) {
        mConversationDataModel = refDataModel;
        mConversationDataModel.getData().addConversationDataListener(mDataListener);
    }

    public ImmutableBindingRef<DraftMessageData> getDraftDataModel() {
        return BindingBase.createBindingReference(mBinding);
    }

    // returns true if it actually shows the subject editor and false if already showing
    public boolean showSubjectEditor() {
        // show the subject editor
        if (mSubjectView.getVisibility() == View.GONE) {
            //HienDTk: them chu thi show keyboard
            showKeyboard();
            mSubjectView.setVisibility(View.VISIBLE);
            mSubjectView.requestFocus();
            return true;
        }
        return false;
    }

    protected void hideSubjectEditor() {
        mSubjectView.setVisibility(View.GONE);
        mComposeEditText.requestFocus();
    }

    /**
     * {@inheritDoc} from TextView.OnEditorActionListener
     */
    @Override // TextView.OnEditorActionListener.onEditorAction
    public boolean onEditorAction(final TextView view, final int actionId, final KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_SEND) {
            sendMessageInternal(true /* checkMessageSize */);
            return true;
        }
        return false;
    }

    protected void updateIcon(){};

    public void sendMessageInternal(final boolean checkMessageSize) {
        LogUtil.i(LogUtil.BUGLE_TAG, "UI initiated message sending in conversation " +
                mBinding.getData().getConversationId());
        if (mBinding.getData().isCheckingDraft()) {
            // Don't send message if we are currently checking draft for sending.
            LogUtil.w(LogUtil.BUGLE_TAG, "Message can't be sent: still checking draft");
            return;
        }
        // Bkav QuangNDb them truong hop check 3G khi gui tin nhan mms
        if (isTurnOff3G()) {
            showToastHasNotConnect3G();
            return;
        }
        //Bkav QuangNDb them th ng dung an send khi dang o giao dien picker
        if ("-1".equals(mBinding.getData().getConversationId())) {
            mHost.sendMessage(null);
            return;
        }
        //Bkav QuangNDb Check xem client da san sang gui tin hay chua
        if (!mHost.isReadySendMessage()) {
            mHost.sendMessage(null);
            return;
        }
        // Check the host for pre-conditions about any action.
        if (mHost.isReadyForAction()) {
            mInputManager.showHideSimSelector(false /* show */, true /* animate */);
            final String messageToSend = getMessageSend();
            mBinding.getData().setMessageText(messageToSend);
            final String subject = mComposeSubjectText.getText().toString();
            mBinding.getData().setMessageSubject(subject);
            // Asynchronously check the draft against various requirements before sending.
            mBinding.getData().checkDraftForAction(checkMessageSize,
                    mHost.getConversationSelfSubId(), new CheckDraftTaskCallback() {
                @Override
                public void onDraftChecked(DraftMessageData data, int result) {
                    mBinding.ensureBound(data);
                    switch (result) {
                        case CheckDraftForSendTask.RESULT_PASSED:
                            // Continue sending after check succeeded.
                            final MessageData message = mBinding.getData()
                                    .prepareMessageForSending(mBinding);

                            if (message != null && message.hasContent()) {
                                // BKav QuangNDb tach code doan play sound message
                                playSentSoundMessage();
                                mHost.sendMessage(message);
                                hideSubjectEditor();
                                if (AccessibilityUtil.isTouchExplorationEnabled(getContext())) {
                                    AccessibilityUtil.announceForAccessibilityCompat(
                                            ComposeMessageView.this, null,
                                            R.string.sending_message);
                                }
                            }
                            break;

                        case CheckDraftForSendTask.RESULT_HAS_PENDING_ATTACHMENTS:
                            // Cannot send while there's still attachment(s) being loaded.
                            UiUtils.showToastAtBottom(
                                    R.string.cant_send_message_while_loading_attachments);
                            break;

                        case CheckDraftForSendTask.RESULT_NO_SELF_PHONE_NUMBER_IN_GROUP_MMS:
                            mHost.promptForSelfPhoneNumber();
                            break;

                        case CheckDraftForSendTask.RESULT_MESSAGE_OVER_LIMIT:
                            Assert.isTrue(checkMessageSize);
                            mHost.warnOfExceedingMessageLimit(
                                    true /*sending*/, false /* tooManyVideos */);
                            break;

                        case CheckDraftForSendTask.RESULT_VIDEO_ATTACHMENT_LIMIT_EXCEEDED:
                            Assert.isTrue(checkMessageSize);
                            mHost.warnOfExceedingMessageLimit(
                                    true /*sending*/, true /* tooManyVideos */);
                            break;

                        case CheckDraftForSendTask.RESULT_SIM_NOT_READY:
                            // Cannot send if there is no active subscription
                            UiUtils.showToastAtBottom(
                                    R.string.cant_send_message_without_active_subscription);
                            break;

                        default:
                            break;
                    }
                }
            }, mBinding);
        } else {
            mHost.warnOfMissingActionConditions(true /*sending*/,
                    new Runnable() {
                        @Override
                        public void run() {
                            sendMessageInternal(checkMessageSize);
                        }

            });
        }
    }

    /**
     * QuangNDb trong TH nem tu ben picker sang ben conversation de lay lai nhap
     */
    public MessageData getDraftHasContentData() {
        final String messageToSend = getMessageSend();
        mBinding.getData().setMessageText(messageToSend);
        final String subject = mComposeSubjectText.getText().toString();
        mBinding.getData().setMessageSubject(subject);
        // Continue sending after check succeeded.
        final MessageData message = mBinding.getData().prepareMessageForDraft(mBinding);
        if (message != null && message.hasContent()) {
            return message;
        }
        return null;
    }

    /**
     * QuangNDb trong TH nem tu ben picker sang ben conversation de lay lai nhap, nhung khong send
     */
    public MessageData getDraftToView() {
        final String messageToSend = getMessageSend();
        mBinding.getData().setMessageText(messageToSend);
        final String subject = mComposeSubjectText.getText().toString();
        mBinding.getData().setMessageSubject(subject);
        // Continue sending after check succeeded.
        final MessageData message = mBinding.getData().prepareMessageForView(mBinding);
        if (message != null && message.hasContent()) {
            return message;
        }
        return null;
    }

    /**
     * QuangNDb trong TH nem tu ben picker sang ben conversation de lay lai nhap
     */
    public MessageData getDraftData() {
        final String messageToSend = getMessageSend();
        mBinding.getData().setMessageText(messageToSend);
        final String subject = mComposeSubjectText.getText().toString();
        mBinding.getData().setMessageSubject(subject);
        // Continue sending after check succeeded.
        final MessageData message = mBinding.getData().prepareMessageForSending(mBinding);
        if (message != null) {
            return message;
        }
        return null;
    }



    public DraftMessageData getDraftMessageData() {
        return mBinding.getData();
    }

    /**
     * QuangNDb get message send
     */
    protected String getMessageSend() {
        return mComposeEditText.getText().toString();
    }


    public static void playSentSound() {
        // Check if this setting is enabled before playing
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final Context context = Factory.get().getApplicationContext();
        final String prefKey = context.getString(R.string.send_sound_pref_key);
        final boolean defaultValue = context.getResources().getBoolean(
                R.bool.send_sound_pref_default);
        if (!prefs.getBoolean(prefKey, defaultValue)) {
            return;
        }
        MediaUtil.get().playSound(context, R.raw.message_sent, null /* completionListener */);
    }

    /**
     * {@inheritDoc} from DraftMessageDataListener
     */
    @Override // From DraftMessageDataListener
    public void onDraftChanged(final DraftMessageData data, final int changeFlags) {
        // As this is called asynchronously when message read check bound before updating text
        mBinding.ensureBound(data);

//        MessageData messageData = null;
//        messageData.getMessageText();
//        messageData.getMmsSubject();
//        messageData.getFirstAttachment();

        // We have to cache the values of the DraftMessageData because when we set
        // mComposeEditText, its onTextChanged calls updateVisualsOnDraftChanged,
        // which immediately reloads the text from the subject and message fields and replaces
        // what's in the DraftMessageData.

        final String subject = data.getMessageSubject();
        final String message = data.getMessageText();
        mFirstDraf = data.getMessageText();

        if ((changeFlags & DraftMessageData.MESSAGE_SUBJECT_CHANGED) ==
                DraftMessageData.MESSAGE_SUBJECT_CHANGED) {
            if (subject != null && !TextUtils.isEmpty(subject)) {
                mComposeSubjectText.setText(subject);
            }

            // Set the cursor selection to the end since setText resets it to the start
            mComposeSubjectText.setSelection(mComposeSubjectText.getText().length());
        }

        if ((changeFlags & DraftMessageData.MESSAGE_TEXT_CHANGED) ==
                DraftMessageData.MESSAGE_TEXT_CHANGED) {
                mComposeEditText.setText(message);
            // Set the cursor selection to the end since setText resets it to the start
            mComposeEditText.setSelection(mComposeEditText.getText().length());
        }

        if ((changeFlags & DraftMessageData.ATTACHMENTS_CHANGED) ==
                DraftMessageData.ATTACHMENTS_CHANGED) {
            final boolean haveAttachments = mAttachmentPreview.onAttachmentsChanged(data);
            mHost.onAttachmentsChanged(haveAttachments);
        }

        if ((changeFlags & DraftMessageData.SELF_CHANGED) == DraftMessageData.SELF_CHANGED) {
            updateOnSelfSubscriptionChange();
        }
        checkMultiSim();
        updateVisualsOnDraftChanged(null);
        if (mHost.isFinishLoadDraf()) {
            sendMessageInternal(true);
        }
    }

    private boolean mIsFirst = true;
    @Override   // From DraftMessageDataListener
    public void onDraftAttachmentLimitReached(final DraftMessageData data) {
        mBinding.ensureBound(data);
        mHost.warnOfExceedingMessageLimit(false /* sending */, false /* tooManyVideos */);
    }

    private void updateOnSelfSubscriptionChange() {
        // Refresh the length filters according to the selected self's MmsConfig.
        mComposeEditText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(mBinding.getData().getSelfSubId())
                        .getMaxTextLimit()) });
        mComposeSubjectText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(mBinding.getData().getSelfSubId())
                        .getMaxSubjectLength())});
    }

    @Override
    public void onMediaItemsSelected(final Collection<MessagePartData> items) {
        mBinding.getData().addAttachments(items);
        announceMediaItemState(true /*isSelected*/);
    }

    @Override
    public void onMediaItemsUnselected(final MessagePartData item) {
        mBinding.getData().removeAttachment(item);
        announceMediaItemState(false /*isSelected*/);
    }

    @Override
    public void onPendingAttachmentAdded(final PendingAttachmentData pendingItem) {
        mBinding.getData().addPendingAttachment(pendingItem, mBinding);
        resumeComposeMessage();
    }

    protected void announceMediaItemState(final boolean isSelected) {
        final Resources res = getContext().getResources();
        final String announcement = isSelected ? res.getString(
                R.string.mediapicker_gallery_item_selected_content_description) :
                    res.getString(R.string.mediapicker_gallery_item_unselected_content_description);
        AccessibilityUtil.announceForAccessibilityCompat(
                this, null, announcement);
    }

    private void announceAttachmentState() {
        if (AccessibilityUtil.isTouchExplorationEnabled(getContext())) {
            int attachmentCount = mBinding.getData().getReadOnlyAttachments().size()
                    + mBinding.getData().getReadOnlyPendingAttachments().size();
            final String announcement = getContext().getResources().getQuantityString(
                    R.plurals.attachment_changed_accessibility_announcement,
                    attachmentCount, attachmentCount);
            AccessibilityUtil.announceForAccessibilityCompat(
                    this, null, announcement);
        }
    }

    @Override
    public void resumeComposeMessage() {
        mComposeEditText.requestFocus();
        mInputManager.showHideImeKeyboard(true, true);
        announceAttachmentState();
    }

    public void clearAttachments() {
        mBinding.getData().clearAttachments(mHost.getAttachmentsClearedFlags());
        mHost.onAttachmentsCleared();
    }

    public void requestDraftMessage(final boolean clearLocalDraft) {
        mBinding.getData().loadFromStorage(mBinding, null, clearLocalDraft);
    }

    public void setDraftMessage(final MessageData message) {
        mBinding.getData().loadFromStorage(mBinding, message, false);
    }

    public void writeDraftMessage() {
        final String messageText = mComposeEditText.getText().toString();
        mBinding.getData().setMessageText(messageText);

        final String subject = mComposeSubjectText.getText().toString();
        mBinding.getData().setMessageSubject(subject);

        mBinding.getData().saveToStorage(mBinding);
    }

    /**Bkav QuangNDb clear draft message*/
    public void clearDraftMessage() {
        mBinding.getData().setMessageText("");
        mBinding.getData().setMessageSubject("");
        mBinding.getData().saveToStorage(mBinding);
    }

    protected void updateConversationSelfId(final String selfId, final boolean notify, final boolean isSendIfDraft) {
        mBinding.getData().setSelfId(selfId, notify);
    }

    // Bkav HuyNQN lay ra slot cua sim mac dinh trong cuoc hoi thoai
    public String getSlotId() {
        return mSlotId;
    }

    public String mSlotId;
    protected Uri getSelfSendButtonIconUri() {
        final Uri overridenSelfUri = mHost.getSelfSendButtonIconUri();
        if (overridenSelfUri != null) {
            return overridenSelfUri;
        }
        final SubscriptionListEntry subscriptionListEntry = getSelfSubscriptionListEntry();

        if (subscriptionListEntry != null) {
            return subscriptionListEntry.selectedIconUri;
        }

        // Fall back to default self-avatar in the base case.
        final ParticipantData self = mConversationDataModel.getData().getDefaultSelfParticipant();
        return self == null ? null : AvatarUriUtil.createAvatarUri(self);
    }

    protected SubscriptionListEntry getSelfSubscriptionListEntry() {
        return mConversationDataModel.getData().getSubscriptionEntryForSelfParticipant(
                mBinding.getData().getSelfId(), false /* excludeDefault */);
    }

    private boolean isDataLoadedForMessageSend() {
        // Check data loading prerequisites for sending a message.
        return mConversationDataModel != null && mConversationDataModel.isBound() &&
                mConversationDataModel.getData().getParticipantsLoaded();
    }
    protected int sendWidgetMode;
    public void updateVisualsOnDraftChanged(String text) {
        if (!mBinding.isBound()){
            return;
        }
        final String messageText = (text == null)? mComposeEditText.getText().toString() : text;
        final DraftMessageData draftMessageData = mBinding.getData();
        draftMessageData.setMessageText(messageText);
        final String subject = mComposeSubjectText.getText().toString();
        draftMessageData.setMessageSubject(subject);
        if (!TextUtils.isEmpty(subject)) {
             mSubjectView.setVisibility(View.VISIBLE);
        }

        final boolean hasMessageText = (TextUtils.getTrimmedLength(messageText) > 0);
        final boolean hasSubject = (TextUtils.getTrimmedLength(subject) > 0);
        final boolean hasWorkingDraft = hasMessageText || hasSubject ||
                mBinding.getData().hasAttachments();
        // Update the SMS text counter.
        final int messageCount = draftMessageData.getNumMessagesToBeSent();
        final int codePointsRemaining = draftMessageData.getCodePointsRemainingInCurrentMessage();
        // Show the counter only if:
        // - We are not in MMS mode
        // - We are going to send more than one message OR we are getting close
        boolean showCounter = false;
        if (!draftMessageData.getIsMms() && (messageCount > 1 || codePointsRemaining <= CODEPOINTS_REMAINING_BEFORE_COUNTER_SHOWN)) {
            showCounter = true;
        }

        if (showCounter) {
            // Update the remaining characters and number of messages required.
            final String counterText = messageCount > 1 ? codePointsRemaining + " / " +
                    messageCount : String.valueOf(codePointsRemaining);
            mCharCounter.setText(counterText);
            showCharCounter();
        } else {
            hideCharCounter();
        }
        // Update the send message button. Self icon uri might be null if self participant data
        // and/or conversation metadata hasn't been loaded by the host.
        final Uri selfSendButtonUri = getSelfSendButtonIconUri();
        sendWidgetMode = SEND_WIDGET_MODE_SELF_AVATAR;
        if (selfSendButtonUri != null && isMultiSim()) {
            //Bkav QuangNDb icon ngay ca khi dang draf
            setSimIconUri(selfSendButtonUri);
            if (hasWorkingDraft && isDataLoadedForMessageSend()) {
                UiUtils.revealOrHideViewWithAnimation(mSendButton, VISIBLE, null);

                if (isOverriddenAvatarAGroup()) {
                    // If the host has overriden the avatar to show a group avatar where the
                    // send button sits, we have to hide the group avatar because it can be larger
                    // than the send button and pieces of the avatar will stick out from behind
                    // the send button.
                    UiUtils.revealOrHideViewWithAnimation(getSimIconView(), GONE, null);
                }
                // Bkav QuangNdb tach code doan nay de override lai o class con
                setVisibleMmsIndicator(draftMessageData.getIsMms());
                sendWidgetMode = SEND_WIDGET_MODE_SEND_BUTTON;
            } else {
                if (isOverriddenAvatarAGroup()) {
                    UiUtils.revealOrHideViewWithAnimation(getSimIconView(), VISIBLE, null);
                }
                UiUtils.revealOrHideViewWithAnimation(mSendButton, GONE, null);
                inVisibleMMsIndicator();
                if (shouldShowSimSelector(mConversationDataModel.getData())) {
                    sendWidgetMode = SEND_WIDGET_MODE_SIM_SELECTOR;
                }
            }
        } else if (!isMultiSim()){
            setImageResourceWithNullUri(hasWorkingDraft,isDataLoadedForMessageSend(),draftMessageData.getIsMms());
        }

        if (mSendWidgetMode != sendWidgetMode || sendWidgetMode == SEND_WIDGET_MODE_SIM_SELECTOR) {
            setSendButtonAccessibility(sendWidgetMode);
            mSendWidgetMode = sendWidgetMode;
        }
        // Update the text hint on the message box depending on the attachment type.
        final List<MessagePartData> attachments = draftMessageData.getReadOnlyAttachments();
        final int attachmentCount = attachments.size();
        if (attachmentCount == 0 && getConditionSetHint()) {
            final SubscriptionListEntry subscriptionListEntry = getSelfSubscriptionListEntry();
            if (subscriptionListEntry == null) {
                setHintNotMultiSim();
            } else {
                // Bkav QuangNDB tach code doan set hint de override lai
                setHintMultiSim(subscriptionListEntry);
               // TODO: 09/05/2020 bit bug ban build rieng de demo
                // Bkav HienDTk: dong
//                setHintNotMultiSim();
            }
        } else if (getConditionSetHint()){
            int type = -1;
            for (final MessagePartData attachment : attachments) {
                int newType;
                if (attachment.isImage()) {
                    newType = ContentType.TYPE_IMAGE;
                } else if (attachment.isAudio()) {
                    newType = ContentType.TYPE_AUDIO;
                } else if (attachment.isVideo()) {
                    newType = ContentType.TYPE_VIDEO;
                } else if (attachment.isVCard()) {
                    newType = ContentType.TYPE_VCARD;
                } else {
                    newType = ContentType.TYPE_OTHER;
                }

                if (type == -1) {
                    type = newType;
                } else if (type != newType || type == ContentType.TYPE_OTHER) {
                    type = ContentType.TYPE_OTHER;
                    break;
                }
            }

            switch (type) {
                case ContentType.TYPE_IMAGE:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_photo, attachmentCount));
                    break;

                case ContentType.TYPE_AUDIO:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_audio, attachmentCount));
                    break;

                case ContentType.TYPE_VIDEO:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_video, attachmentCount));
                    break;

                case ContentType.TYPE_VCARD:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_vcard, attachmentCount));
                    break;

                case ContentType.TYPE_OTHER:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_attachments, attachmentCount));
                    break;

                default:
                    Assert.fail("Unsupported attachment type!");
                    break;
            }
        }
    }

    private static final String TAG = "ComposeMessageView";


    private void setSendButtonAccessibility(final int sendWidgetMode) {
        switch (sendWidgetMode) {
            case SEND_WIDGET_MODE_SELF_AVATAR:
                // No send button and no SIM selector; the self send button is no longer
                // important for accessibility.
                getSimIconView().setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                getSimIconView().setContentDescription(null);
                goneSendButton();
                setSendWidgetAccessibilityTraversalOrder(SEND_WIDGET_MODE_SELF_AVATAR);
                break;

            case SEND_WIDGET_MODE_SIM_SELECTOR:
                getSimIconView().setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
                getSimIconView().setContentDescription(getSimContentDescription());
                setSendWidgetAccessibilityTraversalOrder(SEND_WIDGET_MODE_SIM_SELECTOR);
                break;

            case SEND_WIDGET_MODE_SEND_BUTTON:
                mMmsIndicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                mMmsIndicator.setContentDescription(null);
                setSendWidgetAccessibilityTraversalOrder(SEND_WIDGET_MODE_SEND_BUTTON);
                break;
        }
    }


    private String getSimContentDescription() {
        final SubscriptionListEntry sub = getSelfSubscriptionListEntry();
        if (sub != null) {
            return getResources().getString(
                    R.string.sim_selector_button_content_description_with_selection,
                    sub.displayName);
        } else {
            return getResources().getString(
                    R.string.sim_selector_button_content_description);
        }
    }

    // Set accessibility traversal order of the components in the send widget.
    private void setSendWidgetAccessibilityTraversalOrder(final int mode) {
        if (OsUtil.isAtLeastL_MR1()) {
            mAttachMediaButton.setAccessibilityTraversalBefore(R.id.compose_message_text);
            switch (mode) {
                case SEND_WIDGET_MODE_SIM_SELECTOR:
                    mComposeEditText.setAccessibilityTraversalBefore(R.id.self_send_icon);
                    break;
                case SEND_WIDGET_MODE_SEND_BUTTON:
                    mComposeEditText.setAccessibilityTraversalBefore(R.id.send_message_button);
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public void afterTextChanged(final Editable editable) {
    }

    @Override
    public void beforeTextChanged(final CharSequence s, final int start, final int count,
            final int after) {
        if (mHost.shouldHideAttachmentsWhenSimSelectorShown()) {
            hideSimSelector();
        }
    }

    private void hideSimSelector() {
        if (mInputManager.showHideSimSelector(false /* show */, true /* animate */)) {
            // Now that the sim selector has been hidden, reshow the attachments if they
            // have been hidden.
            hideAttachmentsWhenShowingSims(false /*simPickerVisible*/);
        }
    }

    protected String mOldString = "";
    public String mCurrentString = "";//Bkav QuangNDb Bien luu gia tri text hien tai
    @Override
    public void onTextChanged(final CharSequence s, final int start, final int before,
            final int count) {
        final BugleActionBarActivity activity = (mOriginalContext instanceof BugleActionBarActivity)
                ? (BugleActionBarActivity) mOriginalContext : null;
        if (activity != null && activity.getIsDestroyed()) {
            LogUtil.v(LogUtil.BUGLE_TAG, "got onTextChanged after onDestroy");

            // if we get onTextChanged after the activity is destroyed then, ah, wtf
            // b/18176615
            // This appears to have occurred as the result of orientation change.
            return;
        }
        mBinding.ensureBound();
        updateDraftChange(s.toString());
        // Bkav QuangNDb them ham de bat su kien san sang gui tin va khong them nguoi vao conversation
        onReadySendMessage(s);
    }


    @Override
    public PlainTextEditText getComposeEditText() {
        return mComposeEditText;
    }

    public void displayPhoto(final Uri photoUri, final Rect imageBounds) {
        mHost.displayPhoto(photoUri, imageBounds, true /* isDraft */);
    }

    public void updateConversationSelfIdOnExternalChange(final String selfId) {
        updateConversationSelfId(selfId, true /* notify */, false);
    }

    /**
     * The selfId of the conversation. As soon as the DraftMessageData successfully loads (i.e.
     * getSelfId() is non-null), the selfId in DraftMessageData is treated as the sole source
     * of truth for conversation self id since it reflects any pending self id change the user
     * makes in the UI.
     */
    public String getConversationSelfId() {
        return mBinding.getData().getSelfId();
    }

    public void selectSim(SubscriptionListEntry subscriptionData) {
        final String oldSelfId = getConversationSelfId();
        final String newSelfId = subscriptionData.selfParticipantId;
        Assert.notNull(newSelfId);
        // Don't attempt to change self if self hasn't been loaded, or if self hasn't changed.
        if (TextUtils.equals(oldSelfId, newSelfId)) {
            //Bkav QuangNDb khong gui message khi chon sim nua
//            sendMessageIfHaveDraf();
            return;
        }
        updateConversationSelfId(newSelfId, true /* notify */, true);
    }


    /**
     * QuangNDB
     */
    protected boolean getConditionOldSelfId(String oldSelfId) {
        return oldSelfId == null;
    }

    public void hideAllComposeInputs(final boolean animate) {
        mInputManager.hideAllInputs(animate);
    }

    public void saveInputState(final Bundle outState) {
        mInputManager.onSaveInputState(outState);
    }

    public void resetMediaPickerState() {
        mInputManager.resetMediaPickerState();
    }

    public boolean onBackPressed() {
        return mInputManager.onBackPressed();
    }

    public boolean onNavigationUpPressed() {
        return mInputManager.onNavigationUpPressed();
    }

    public boolean updateActionBar(final ActionBar actionBar) {
        boolean isUpdate = mInputManager != null && mInputManager.updateActionBar(actionBar);
        return isUpdate;
    }

    public static boolean shouldShowSimSelector(final ConversationData convData) {
        return OsUtil.isAtLeastL_MR1() &&
                convData.getSelfParticipantsCountExcludingDefault(true /* activeOnly */) > 1;
    }

    public void sendMessageIgnoreMessageSizeLimit() {
        sendMessageInternal(false /* checkMessageSize */);
    }

    public void onAttachmentPreviewLongClicked() {
        mHost.showAttachmentChooser();
    }

    @Override
    public void onDraftAttachmentLoadFailed() {
        mHost.notifyOfAttachmentLoadFailed();
    }

    protected boolean isOverriddenAvatarAGroup() {
        final Uri overridenSelfUri = mHost.getSelfSendButtonIconUri();
        if (overridenSelfUri == null) {
            return false;
        }
        return AvatarUriUtil.TYPE_GROUP_URI.equals(AvatarUriUtil.getAvatarType(overridenSelfUri));
    }

    @Override
    public void setAccessibility(boolean enabled) {
        if (enabled) {
            mAttachMediaButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            mComposeEditText.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            mSendButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setSendButtonAccessibility(mSendWidgetMode);
        } else {
            getSimIconView().setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mComposeEditText.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mSendButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mAttachMediaButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    /**
     * ----------------------------------------------------------------BKAV-------------------------------------------------
     * Bkav QuangNDb: tach code long click cua send button de custom lai o lop con
     *
     */
    protected void onLongClickSendButton() {
        mSendButton.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(final View v) {
                if (mHost.shouldShowSubjectEditor()) {
                    showSubjectEditor();
                } else {
                    boolean shown = mInputManager.toggleSimSelector(true /* animate */,
                            getSelfSubscriptionListEntry());
                    hideAttachmentsWhenShowingSims(shown);
                }
                return true;
            }
        });
    }

    /**
     * Bkav QuangNDb tach code doan should show subject
     */
    protected void shouldShowSubject() {
        if (mHost.shouldShowSubjectEditor()) {
            showSubjectEditor();
        }
    }

    /**
     * Bkav QuangNDb: tach code long click cua SelfSendButton de custom lai o lop con
     */
    protected void onLongClickSelfSendButton() {
        getSimIconView().setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(final View v) {
                if (mHost.shouldShowSubjectEditor()) {
                    showSubjectEditor();
                } else {
                    boolean shown = mInputManager.toggleSimSelector(true /* animate */,
                            getSelfSubscriptionListEntry());
                    hideAttachmentsWhenShowingSims(shown);
                }
                return true;
            }
        });
    }

    /**
     * Bkav QuangNDb tach code doan set hide/show mmsIndicator
     */
    protected void setVisibleMmsIndicator(boolean isMms) {
        mMmsIndicator.setVisibility(isMms ? VISIBLE : INVISIBLE);
    }
    /**
     * Bkav QuangNDb tach code doan set hide/show mmsIndicator
     */
    protected void inVisibleMMsIndicator() {
        mMmsIndicator.setVisibility(INVISIBLE);
    }
    /**
     * Bkav QuangNDb tach code doan set hide/show CharCounter
     */
    protected void showCharCounter() {
        mCharCounter.setVisibility(View.VISIBLE);
    }
    /**
     * Bkav QuangNDb tach code doan set hide/show CharCounter
     */
    protected void hideCharCounter() {
        mCharCounter.setVisibility(View.INVISIBLE);
    }

    /**
     * Bkav QuangNDb tach code doan set hint multi sim de override lai
     */
    protected void setHintMultiSim(SubscriptionListEntry subscriptionListEntry) {
        mComposeEditText.setHint(Html.fromHtml(getResources().getString(
                R.string.compose_message_view_hint_text_multi_sim,
                subscriptionListEntry.displayName)));
    }

    /**
     * Bkav QuangNDb tach code set ImageResourceNull uri
     * @param workingDraft
     * @param hasWorkingDraft
     */
    protected void setImageResourceWithNullUri(boolean workingDraft, boolean hasWorkingDraft, boolean isMms) {
        mSelfSendIcon.setImageResourceUri(null);
    }

    /**
     * Bkav QuangNDb gone button send
     */
    protected void goneSendButton() {
        mSendButton.setVisibility(View.GONE);
    }

    /**
     * Bkav QuangNDb check multi sim
     */
    public boolean isMultiSim() {
        return mIsMultiSim;
    }

    /**
     * Bkav QuangNDb check dieu kien khong phai multisim
     */
    protected void setHintNotMultiSim() {
        mComposeEditText.setHint(R.string.compose_message_view_hint_text);
    }

    /**
     * QuangNDb get condition set hint
     */
    protected boolean getConditionSetHint() {
        return true;
    }

    /**
     * Bkav QuangNDb check dieu kien san sang gui tin nhan den dia chi da xac dinh
     */
    protected void onReadySendMessage(CharSequence s) {
    }

    /**
     * Bkav QuangNDb getAttachment view
     */
    protected AttachmentPreview getAttachmentView() {
        return (AttachmentPreview) findViewById(R.id.attachment_draft_view);
    }

    protected void hideKeyboard() {

    }

    // Bkav QuangNDb tach code don play sent sound
    protected void playSentSoundMessage() {
        playSentSound();
    }

    // Bkav QuangNDb check xem 3G co tat khong de xem co gui MMS hay khong
    protected boolean isTurnOff3G() {
        return false;
    }

    /**
     * Bkav QuangNDb them ham show toast khi khong ket noi toi 3G
     */
    protected void showToastHasNotConnect3G() {
        // Bkav QuangNDb khong lam gi, custom lai o class con
    }

    /**
     * Quangndb an media picker di
     */
    protected void hideMediaPicker() {
    }

    /**
     * QuangNDb clear forcus khi bat attach
     */
    protected void clearEditTextFocus() {
    }

    /**
     * QuangNDb show key board
     */
    protected void showKeyboard(){

    }

    /**
     * QuangNDb clear forcus khi bat attach
     */
    protected void requestEditTextFocus() {
    }



    /**
     * QuangNDb show or hide medi picker
     */
    protected void showOrHideMediaPicker(boolean isShow) {
        mInputManager.showHideMediaPicker(true /* show */, true /* animate */);
    }

    /**
     * Bkav QuangNDb Update draft change
     */
    protected void updateDraftChange(String text) {
        updateVisualsOnDraftChanged(text);
    }

    /**Bkav QuangNDb set uri cho self send icon*/
    protected void setSimIconUri(Uri selfSendButtonUri) {
        mSelfSendIcon.setImageResourceUri(selfSendButtonUri);
    }

    /**Bkav QuangNDb get SimIconView*/
    protected ImageView getSimIconView() {
        return mSelfSendIcon;
    }

    /**Bkav QuangNDb click vao nut send*/
    protected void clickSendButton() {
        sendMessageInternal(true /* checkMessageSize */);
    }

    /**Bkav QuangNDb long click vao attach button*/
    protected void longClickAttachButton() {

    }

    /**Bkav QuangNDb send message if have draf*/
    protected void sendMessageIfHaveDraf() {
    }

    /**Bkav QuangNDb Lay view emoticon ra*/
    public View getEmoticonView() {
        return null;
    }
    /**Bkav QuangNDb send message with quick response*/
    public void sendMessageWithQuickResponse(final Object data) {
    }

    /**Bkav QuangNDb set view cha*/
    public void setParentView(ViewGroup mParentView) {
        this.mParentView = mParentView;
    }

    /**Bkav QuangNDb check xem co phai deep shortcut dang show hay khong*/
    public boolean isDeepShortcutTouch(MotionEvent ev) {
        return false;
    }
    /**Bkav QuangNDb khoi tao compose edit text*/
    protected void initComposeEditText() {
        mComposeEditText = (PlainTextEditText) findViewById(
                R.id.compose_message_text);
    }

    public void hideInput() {
        if (mInputManager.getMediaInput().isOpen()) {
            mInputManager.showHideMediaPicker(false /* show */, false /* animate */);
        }
    }

    //Bkav QuangNDb an custom input nhu emoji di

    // Bkav HuyNQN cai dat icon sim
    public void setImageResourceButtonSend(Uri uri){};

    private boolean mIsSend; // Bkav HuyNQN bien nay dung de xu ly long click thi ko gui tin nhan di luon ma chi chon sim

    public void setSend(boolean send) {
        mIsSend = send;
    }

    public boolean isSend() {
        return mIsSend;
    }

    //Bkav QuangNDb sua protect de sub dung
    protected void checkMultiSim() {

    }

    //Bkav QuangNDb update res icon cua button sim va button send
    public void updateResFromProfile(Object o) {

    }

    //Bkav QuangNDb custom lai o sub
    protected void clickSelfSendIcon() {
        boolean shown = mInputManager.toggleSimSelector(true /* animate */,
                getSelfSubscriptionListEntry());
        hideAttachmentsWhenShowingSims(shown);
    }



    /**
     * HienDTk: lay slotIndex cua sim mac dinh
     */
    public  static int getDefaultSimSMS(Context context) {
        try {
            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            final SubscriptionInfo sir = (SubscriptionInfo) SubscriptionManager.class.getMethod("getDefaultSmsSubscriptionInfo").invoke(subscriptionManager);
            if (sir != null) {
                return sir.getSimSlotIndex();
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
        return -1;
    }

    // Bkav HuyNQN kiem tra xem nguoi dung co dang cai dat la luon hoi khi gui tin nhan hay khong.
    private boolean mIsAskBeforeSendSms;
    public boolean isIsAskBeforeSendSms() {
        return mIsAskBeforeSendSms;
    }
    public void setIsAskBeforeSendSms(boolean mIsAskBeforeSendSms) {
        this.mIsAskBeforeSendSms = mIsAskBeforeSendSms;
    }
}
