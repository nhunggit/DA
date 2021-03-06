package bkav.android.btalk.messaging.ui.conversationlist;

import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CoordinatorLayout;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import bkav.android.btalk.R;
import bkav.android.btalk.activities.BtalkActivity;

import static com.android.common.util.DeviceVersionUtil.isBL01Device;

public class MessageHeaderBehaviour extends CoordinatorLayout.Behavior<MessageHeaderView> {

    private Context mContext;

    private int mStartMarginRight;
    private int mEndMarginRight;
    private int mStartMarginBottom;
    private boolean isHide;

    private ImageView mSearchButton;
    private ImageView mImageBackgroundExpandLayout;
    private ImageView mStartNewConversationButton;
    private ImageView mStartNewConversationButtonSmall;

    public MessageHeaderBehaviour(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    public MessageHeaderBehaviour(Context context, AttributeSet attrs, Context mContext) {
        super(context, attrs);
        this.mContext = mContext;
    }

    public int getToolbarHeight(Context context) {
        int result = 0;
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            result = TypedValue.complexToDimensionPixelSize(tv.data, context.getResources().getDisplayMetrics());
        }
        return result;
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull MessageHeaderView child, @NonNull View dependency) {
        return dependency instanceof AppBarLayout;
    }

    // Bkav TienNAb: update header view khi scroll appbarlayout
    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull MessageHeaderView child, @NonNull View dependency) {
        shouldInitProperties();
        initView(parent,child);

        int maxScroll = ((AppBarLayout) dependency).getTotalScrollRange();
        float percentage = Math.abs(dependency.getY()) / (float) maxScroll;
        float childPosition = dependency.getHeight()
                + dependency.getY()
                - child.getHeight()
                - (getToolbarHeight(mContext) - child.getHeight()) * percentage / 2;

        childPosition = childPosition - mStartMarginBottom * (1f - percentage);
        float imageAlpha = 1 - (Math.abs(dependency.getY()) / (float) maxScroll);

        // Bkav TienNAb: hieu ung fly cua button search
        RelativeLayout.LayoutParams searchButtonParams = (RelativeLayout.LayoutParams) mSearchButton.getLayoutParams();
        int rightMarginSearchButton = (int) (mEndMarginRight + imageAlpha * (mStartMarginRight - mEndMarginRight));
        searchButtonParams.rightMargin = rightMarginSearchButton;
        mSearchButton.setLayoutParams(searchButtonParams);

        // Bkav TienNAb: hieu ung an/hien cua icon tin nhan lon
        mImageBackgroundExpandLayout.setAlpha(imageAlpha);

        // Bkav TienNAb: hieu ung an/hien cua button them tin nhan o phia duoi
        mStartNewConversationButton.setAlpha(1 - imageAlpha);
        mStartNewConversationButton.setScaleX(1 - imageAlpha);
        mStartNewConversationButton.setScaleY(1 - imageAlpha);

        // Bkav TienNAb: hieu ung fly cua button them tin nhan moi o phia tren
        RelativeLayout.LayoutParams mNewConversationButtonSmallParams = (RelativeLayout.LayoutParams) mStartNewConversationButtonSmall.getLayoutParams();
        int bottomMarginNewConversationButtonSmall;
        // Bkav HaiKH - Fix bug BOS-3201- Start
        // sua lai vi tri hien thi cua floating button trong cac truong hop tren BL01
        if(isBL01Device()){
            if (getActivity() instanceof BtalkForwardMessageActivity){
                if (BtalkActivity.hasNavigationBar()){
                    bottomMarginNewConversationButtonSmall = (int) (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_forward_message_activity_has_navigation_bar_bl01)
                            - imageAlpha * (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_forward_message_activity_has_navigation_bar_bl01)
                            - mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_start_forward_message_activity_has_navigation_bar_bl01)));
                } else {
                    bottomMarginNewConversationButtonSmall = (int) (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_forward_message_activity_bl01)
                            - imageAlpha * (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_forward_message_activity_bl01)
                            - mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_start_forward_message_activity_bl01)));
                }
            } else {
                if (BtalkActivity.hasNavigationBar()){
                    bottomMarginNewConversationButtonSmall = (int) (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_has_navigation_bar_bl01)
                            - imageAlpha * (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_has_navigation_bar_bl01)
                            - mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_start_has_navigation_bar_bl01)));
                } else {
                    bottomMarginNewConversationButtonSmall = (int) (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_bl01)
                            - imageAlpha * (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_bl01)
                            - mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_start_bl01)));
                }
            }
            // Bkav HaiKH - Fix bug BOS-3201- End
        }else {
            // Bkav TienNAb: sua lai vi tri hien thi cua floating button trong cac truong hop
            if (getActivity() instanceof BtalkForwardMessageActivity){
                if (BtalkActivity.hasNavigationBar()){
                    bottomMarginNewConversationButtonSmall = (int) (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_forward_message_activity_has_navigation_bar)
                            - imageAlpha * (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_forward_message_activity_has_navigation_bar)
                            - mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_start_forward_message_activity_has_navigation_bar)));
                } else {
                    bottomMarginNewConversationButtonSmall = (int) (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_forward_message_activity)
                            - imageAlpha * (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_forward_message_activity)
                            - mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_start_forward_message_activity)));
                }
            } else {
                if (BtalkActivity.hasNavigationBar()){
                    bottomMarginNewConversationButtonSmall = (int) (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_has_navigation_bar)
                            - imageAlpha * (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end_has_navigation_bar)
                            - mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_start_has_navigation_bar)));
                } else {
                    bottomMarginNewConversationButtonSmall = (int) (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end)
                            - imageAlpha * (mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_end)
                            - mContext.getResources().getDimensionPixelOffset(R.dimen.floating_action_button_small_margin_bottom_start)));
                }
            }
        }
        mNewConversationButtonSmallParams.bottomMargin = bottomMarginNewConversationButtonSmall;
        mStartNewConversationButtonSmall.setLayoutParams(mNewConversationButtonSmallParams);
        mStartNewConversationButtonSmall.setAlpha(imageAlpha);
        mStartNewConversationButtonSmall.setScaleX(imageAlpha);
        mStartNewConversationButtonSmall.setScaleY(imageAlpha);



        child.setY(childPosition);

        if (isHide && percentage < 1) {
            child.setVisibility(View.VISIBLE);
            isHide = false;
        } else if (!isHide && percentage == 1) {
            child.setVisibility(View.GONE);
            isHide = true;
        }
        return true;
    }

    private void initView(@NonNull CoordinatorLayout parent, @NonNull MessageHeaderView child){
        mSearchButton = child.findViewById(R.id.img_ic_search_expand);
        mImageBackgroundExpandLayout = parent.findViewById(R.id.img_background_expand_layout);
        mStartNewConversationButtonSmall = parent.findViewById(R.id.new_conversation_button);
        mStartNewConversationButton = parent.findViewById(R.id.start_new_conversation_button);
    }

    private Activity getActivity(){
        return (Activity) mContext;
    }

    // Bkav TienNAb: gan cac gia tri cho layout header view
    private void shouldInitProperties() {
        if (mStartMarginBottom == 0) {
            mStartMarginBottom = mContext.getResources().getDimensionPixelOffset(R.dimen.message_header_view_start_margin_bottom);
        }

        if (mStartMarginRight == 0) {
            mStartMarginRight = mContext.getResources().getDimensionPixelOffset(R.dimen.message_header_view_start_margin_right);
        }

        if (mEndMarginRight == 0){
            mEndMarginRight = mContext.getResources().getDimensionPixelOffset(R.dimen.message_header_view_end_margin_right);
        }
    }


}
