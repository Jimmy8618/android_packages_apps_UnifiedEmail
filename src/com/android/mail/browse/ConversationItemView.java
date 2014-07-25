/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
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

package com.android.mail.browse;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipData.Item;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.support.v4.text.TextUtilsCompat;
import android.support.v4.view.ViewCompat;
import android.text.Layout.Alignment;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.text.format.DateUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.TextAppearanceSpan;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.android.mail.R;
import com.android.mail.analytics.Analytics;
import com.android.mail.bitmap.CheckableContactFlipDrawable;
import com.android.mail.bitmap.ContactDrawable;
import com.android.mail.perf.Timer;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.ConversationColumns;
import com.android.mail.providers.UIProvider.ConversationListIcon;
import com.android.mail.providers.UIProvider.FolderType;
import com.android.mail.ui.AnimatedAdapter;
import com.android.mail.ui.ControllableActivity;
import com.android.mail.ui.ConversationSelectionSet;
import com.android.mail.ui.ConversationSetObserver;
import com.android.mail.ui.DividedImageCanvas.InvalidateCallback;
import com.android.mail.ui.FolderDisplayer;
import com.android.mail.ui.SwipeableItemView;
import com.android.mail.ui.SwipeableListView;
import com.android.mail.ui.ViewMode;
import com.android.mail.utils.FolderUri;
import com.android.mail.utils.HardwareLayerEnabler;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;
import com.android.mail.utils.ViewUtils;
import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class ConversationItemView extends View
        implements SwipeableItemView, ToggleableItem, InvalidateCallback, ConversationSetObserver,
        BadgeSpan.BadgeSpanDimensions {

    // Timer.
    private static int sLayoutCount = 0;
    private static Timer sTimer; // Create the sTimer here if you need to do
                                 // perf analysis.
    private static final int PERF_LAYOUT_ITERATIONS = 50;
    private static final String PERF_TAG_LAYOUT = "CCHV.layout";
    private static final String PERF_TAG_CALCULATE_TEXTS_BITMAPS = "CCHV.txtsbmps";
    private static final String PERF_TAG_CALCULATE_SENDER_SUBJECT = "CCHV.sendersubj";
    private static final String PERF_TAG_CALCULATE_FOLDERS = "CCHV.folders";
    private static final String PERF_TAG_CALCULATE_COORDINATES = "CCHV.coordinates";
    private static final String LOG_TAG = LogTag.getLogTag();

    // Static bitmaps.
    private static Bitmap STAR_OFF;
    private static Bitmap STAR_ON;
    private static Bitmap ATTACHMENT;
    private static Bitmap ONLY_TO_ME;
    private static Bitmap TO_ME_AND_OTHERS;
    private static Bitmap IMPORTANT_ONLY_TO_ME;
    private static Bitmap IMPORTANT_TO_ME_AND_OTHERS;
    private static Bitmap IMPORTANT;
    private static Bitmap STATE_REPLIED;
    private static Bitmap STATE_FORWARDED;
    private static Bitmap STATE_REPLIED_AND_FORWARDED;
    private static Bitmap STATE_CALENDAR_INVITE;
    private static Bitmap VISIBLE_CONVERSATION_CARET;
    private static Drawable RIGHT_EDGE_TABLET;

    private static String sSendersSplitToken;
    private static String sElidedPaddingToken;

    // Static colors.
    private static int sSendersTextColor;
    private static int sDateTextColorRead;
    private static int sDateTextColorUnread;
    private static int sStarTouchSlop;
    private static int sSenderImageTouchSlop;
    private static int sShrinkAnimationDuration;
    private static int sSlideAnimationDuration;
    private static int sCabAnimationDuration;
    private static int sBadgePaddingExtraWidth;
    private static int sBadgeRoundedCornerRadius;
    private static int sFolderRoundedCornerRadius;

    // Static paints.
    private static final TextPaint sPaint = new TextPaint();
    private static final TextPaint sFoldersPaint = new TextPaint();
    private static final Paint sCheckBackgroundPaint = new Paint();

    private static BroadcastReceiver sConfigurationChangedReceiver;

    // Backgrounds for different states.
    private final SparseArray<Drawable> mBackgrounds = new SparseArray<Drawable>();

    // Dimensions and coordinates.
    private int mViewWidth = -1;
    /** The view mode at which we calculated mViewWidth previously. */
    private int mPreviousMode;

    private int mInfoIconX;
    private int mDateX;
    private int mDateWidth;
    private int mPaperclipX;
    private int mSendersX;
    private int mSendersWidth;

    /** Whether we are on a tablet device or not */
    private final boolean mTabletDevice;
    /** Whether we are on an expansive tablet */
    private final boolean mIsExpansiveTablet;
    /** When in conversation mode, true if the list is hidden */
    private final boolean mListCollapsible;

    @VisibleForTesting
    ConversationItemViewCoordinates mCoordinates;

    private ConversationItemViewCoordinates.Config mConfig;

    private final Context mContext;

    public ConversationItemViewModel mHeader;
    private boolean mDownEvent;
    private boolean mSelected = false;
    private ConversationSelectionSet mSelectedConversationSet;
    private Folder mDisplayedFolder;
    private boolean mStarEnabled;
    private boolean mSwipeEnabled;
    private int mLastTouchX;
    private int mLastTouchY;
    private AnimatedAdapter mAdapter;
    private float mAnimatedHeightFraction = 1.0f;
    private final String mAccount;
    private ControllableActivity mActivity;
    private final TextView mSendersTextView;
    private final TextView mSubjectTextView;
    private final TextView mSnippetTextView;
    private int mGadgetMode;

    private static int sFoldersStartPadding;
    private static TextAppearanceSpan sSubjectTextUnreadSpan;
    private static TextAppearanceSpan sSubjectTextReadSpan;
    private static TextAppearanceSpan sBadgeTextSpan;
    private static BackgroundColorSpan sBadgeBackgroundSpan;
    private static ForegroundColorSpan sSnippetTextSpan;
    private static int sScrollSlop;
    private static CharacterStyle sActivatedTextSpan;

    private final CheckableContactFlipDrawable mSendersImageView;

    /** The resource id of the color to use to override the background. */
    private int mBackgroundOverrideResId = -1;
    /** The bitmap to use, or <code>null</code> for the default */
    private Bitmap mPhotoBitmap = null;
    private Rect mPhotoRect = null;

    /**
     * A listener for clicks on the various areas of a conversation item.
     */
    public interface ConversationItemAreaClickListener {
        /** Called when the info icon is clicked. */
        void onInfoIconClicked();

        /** Called when the star is clicked. */
        void onStarClicked();
    }

    /** If set, it will steal all clicks for which the interface has a click method. */
    private ConversationItemAreaClickListener mConversationItemAreaClickListener = null;

    static {
        sPaint.setAntiAlias(true);
        sFoldersPaint.setAntiAlias(true);

        sCheckBackgroundPaint.setColor(Color.GRAY);
    }

    /**
     * Handles displaying folders in a conversation header view.
     */
    static class ConversationItemFolderDisplayer extends FolderDisplayer {

        private int mFoldersCount;

        public ConversationItemFolderDisplayer(Context context) {
            super(context);
        }

        @Override
        public void loadConversationFolders(Conversation conv, final FolderUri ignoreFolderUri,
                final int ignoreFolderType) {
            super.loadConversationFolders(conv, ignoreFolderUri, ignoreFolderType);
            mFoldersCount = mFoldersSortedSet.size();
        }

        @Override
        public void reset() {
            super.reset();
            mFoldersCount = 0;
        }

        public boolean hasVisibleFolders() {
            return mFoldersCount > 0;
        }

        private int measureFolders(int availableSpace, int cellSize) {
            int totalWidth = 0;
            boolean firstTime = true;
            for (Folder f : mFoldersSortedSet) {
                final String folderString = f.name;
                int width = (int) sFoldersPaint.measureText(folderString) + cellSize;
                if (firstTime) {
                    firstTime = false;
                } else {
                    width += sFoldersStartPadding;
                }
                totalWidth += width;
                if (totalWidth > availableSpace) {
                    break;
                }
            }

            return totalWidth;
        }

        public void drawFolders(
                Canvas canvas, ConversationItemViewCoordinates coordinates, boolean isRtl) {
            if (mFoldersCount == 0) {
                return;
            }
            final int left = coordinates.foldersLeft;
            final int right = coordinates.foldersRight;
            final int y = coordinates.foldersY;
            final int height = coordinates.foldersHeight;
            final int textBottomPadding = coordinates.foldersTextBottomPadding;

            sFoldersPaint.setTextSize(coordinates.foldersFontSize);
            sFoldersPaint.setTypeface(coordinates.foldersTypeface);

            // Initialize space and cell size based on the current mode.
            int availableSpace = right - left;
            int maxFoldersCount = availableSpace / coordinates.getFolderMinimumWidth();
            int foldersCount = Math.min(mFoldersCount, maxFoldersCount);
            int averageWidth = availableSpace / foldersCount;
            int cellSize = coordinates.getFolderCellWidth();

            // TODO(ath): sFoldersPaint.measureText() is done 3x in this method. stop that.
            // Extra credit: maybe cache results across items as long as font size doesn't change.

            final int totalWidth = measureFolders(availableSpace, cellSize);
            int xLeft = (isRtl) ? left : right - Math.min(availableSpace, totalWidth);
            final boolean overflow = totalWidth > availableSpace;

            // Second pass to draw folders.
            int i = 0;
            for (Iterator<Folder> it = isRtl ?
                    mFoldersSortedSet.descendingIterator() : mFoldersSortedSet.iterator();
                    it.hasNext();) {
                final Folder f = it.next();
                if (availableSpace <= 0) {
                    break;
                }
                final String folderString = f.name;
                final int fgColor = f.getForegroundColor(mDefaultFgColor);
                final int bgColor = f.getBackgroundColor(mDefaultBgColor);
                boolean labelTooLong = false;
                final int textW = (int) sFoldersPaint.measureText(folderString);
                int width = textW + cellSize + sFoldersStartPadding;

                if (overflow && width > averageWidth) {
                    if (i < foldersCount - 1) {
                        width = averageWidth;
                    } else {
                        // allow the last label to take all remaining space
                        // (and don't let it make room for padding)
                        width = availableSpace + sFoldersStartPadding;
                    }
                    labelTooLong = true;
                }

                // Draw the box.
                sFoldersPaint.setColor(bgColor);
                sFoldersPaint.setStyle(Paint.Style.FILL);
                final RectF rect =
                        new RectF(xLeft, y, xLeft + width - sFoldersStartPadding, y + height);
                canvas.drawRoundRect(rect, sFolderRoundedCornerRadius, sFolderRoundedCornerRadius,
                        sFoldersPaint);

                // Draw the text.
                final int padding = cellSize / 2;
                sFoldersPaint.setColor(fgColor);
                sFoldersPaint.setStyle(Paint.Style.FILL);
                if (labelTooLong) {
                    // todo - take RTL into account for fade
                    final int rightBorder = xLeft + width - sFoldersStartPadding - padding;
                    final Shader shader = new LinearGradient(rightBorder - padding, y,
                            rightBorder, y, fgColor, Utils.getTransparentColor(fgColor),
                            Shader.TileMode.CLAMP);
                    sFoldersPaint.setShader(shader);
                }
                canvas.drawText(folderString, xLeft + padding, y + height - textBottomPadding,
                        sFoldersPaint);
                if (labelTooLong) {
                    sFoldersPaint.setShader(null);
                }

                availableSpace -= width;
                xLeft += width;
                i++;
            }
        }
    }

    public ConversationItemView(Context context, String account) {
        super(context);
        Utils.traceBeginSection("CIVC constructor");
        setClickable(true);
        setLongClickable(true);
        mContext = context.getApplicationContext();
        final Resources res = mContext.getResources();
        mTabletDevice = Utils.useTabletUI(res);
        mIsExpansiveTablet =
                mTabletDevice ? res.getBoolean(R.bool.use_expansive_tablet_ui) : false;
        mListCollapsible = res.getBoolean(R.bool.list_collapsible);
        mAccount = account;

        getItemViewResources(mContext);

        final int layoutDir = TextUtilsCompat.getLayoutDirectionFromLocale(Locale.getDefault());

        mSendersTextView = new TextView(mContext);
        mSendersTextView.setIncludeFontPadding(false);

        mSubjectTextView = new TextView(mContext);
        mSubjectTextView.setEllipsize(TextUtils.TruncateAt.END);
        mSubjectTextView.setSingleLine(); // allow partial words to be elided
        mSubjectTextView.setIncludeFontPadding(false);
        ViewCompat.setLayoutDirection(mSubjectTextView, layoutDir);
        ViewUtils.setTextAlignment(mSubjectTextView, View.TEXT_ALIGNMENT_VIEW_START);

        mSnippetTextView = new TextView(mContext);
        mSnippetTextView.setEllipsize(TextUtils.TruncateAt.END);
        mSnippetTextView.setSingleLine(); // allow partial words to be elided
        mSnippetTextView.setIncludeFontPadding(false);
        ViewCompat.setLayoutDirection(mSnippetTextView, layoutDir);
        ViewUtils.setTextAlignment(mSnippetTextView, View.TEXT_ALIGNMENT_VIEW_START);

        mSendersImageView = new CheckableContactFlipDrawable(res, sCabAnimationDuration);
        mSendersImageView.setCallback(this);

        Utils.traceEndSection();
    }

    private static synchronized void getItemViewResources(Context context) {
        if (sConfigurationChangedReceiver == null) {
            sConfigurationChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    STAR_OFF = null;
                    getItemViewResources(context);
                }
            };
            context.registerReceiver(sConfigurationChangedReceiver, new IntentFilter(
                    Intent.ACTION_CONFIGURATION_CHANGED));
        }
        if (STAR_OFF == null) {
            final Resources res = context.getResources();
            // Initialize static bitmaps.
            STAR_OFF = BitmapFactory.decodeResource(res, R.drawable.ic_star_outline_20dp);
            STAR_ON = BitmapFactory.decodeResource(res, R.drawable.ic_star_20dp);
            ATTACHMENT = BitmapFactory.decodeResource(res, R.drawable.ic_attach_file_20dp);
            ONLY_TO_ME = BitmapFactory.decodeResource(res, R.drawable.ic_email_caret_double);
            TO_ME_AND_OTHERS = BitmapFactory.decodeResource(res, R.drawable.ic_email_caret_single);
            IMPORTANT_ONLY_TO_ME = BitmapFactory.decodeResource(res,
                    R.drawable.ic_email_caret_double_important_unread);
            IMPORTANT_TO_ME_AND_OTHERS = BitmapFactory.decodeResource(res,
                    R.drawable.ic_email_caret_single_important_unread);
            IMPORTANT = BitmapFactory.decodeResource(res,
                    R.drawable.ic_email_caret_none_important_unread);
            STATE_REPLIED =
                    BitmapFactory.decodeResource(res, R.drawable.ic_badge_reply_holo_light);
            STATE_FORWARDED =
                    BitmapFactory.decodeResource(res, R.drawable.ic_badge_forward_holo_light);
            STATE_REPLIED_AND_FORWARDED =
                    BitmapFactory.decodeResource(res, R.drawable.ic_badge_reply_forward_holo_light);
            STATE_CALENDAR_INVITE =
                    BitmapFactory.decodeResource(res, R.drawable.ic_badge_invite_holo_light);
            VISIBLE_CONVERSATION_CARET = BitmapFactory.decodeResource(res, R.drawable.caret_grey);
            RIGHT_EDGE_TABLET = res.getDrawable(R.drawable.list_edge_tablet);

            // Initialize colors.
            sActivatedTextSpan = CharacterStyle.wrap(new ForegroundColorSpan(
                    res.getColor(R.color.senders_text_color)));
            sSendersTextColor = res.getColor(R.color.senders_text_color);
            sSubjectTextUnreadSpan = new TextAppearanceSpan(context,
                    R.style.SubjectAppearanceUnreadStyle);
            sBadgeTextSpan = new TextAppearanceSpan(context, R.style.BadgeTextStyle);
            sBadgeBackgroundSpan = new BackgroundColorSpan(
                    res.getColor(R.color.badge_background_color));
            sSubjectTextReadSpan = new TextAppearanceSpan(
                    context, R.style.SubjectAppearanceReadStyle);
            sSnippetTextSpan = new ForegroundColorSpan(res.getColor(R.color.snippet_text_color));
            sDateTextColorRead = res.getColor(R.color.date_text_color_read);
            sDateTextColorUnread = res.getColor(R.color.date_text_color_unread);
            sStarTouchSlop = res.getDimensionPixelSize(R.dimen.star_touch_slop);
            sSenderImageTouchSlop = res.getDimensionPixelSize(R.dimen.sender_image_touch_slop);
            sShrinkAnimationDuration = res.getInteger(R.integer.shrink_animation_duration);
            sSlideAnimationDuration = res.getInteger(R.integer.slide_animation_duration);
            // Initialize static color.
            sSendersSplitToken = res.getString(R.string.senders_split_token);
            sElidedPaddingToken = res.getString(R.string.elided_padding_token);
            sScrollSlop = res.getInteger(R.integer.swipeScrollSlop);
            sFoldersStartPadding = res.getDimensionPixelOffset(R.dimen.folders_start_padding);
            sCabAnimationDuration = res.getInteger(R.integer.conv_item_view_cab_anim_duration);
            sBadgePaddingExtraWidth = res.getDimensionPixelSize(R.dimen.badge_padding_extra_width);
            sBadgeRoundedCornerRadius =
                    res.getDimensionPixelSize(R.dimen.badge_rounded_corner_radius);
            sFolderRoundedCornerRadius =
                    res.getDimensionPixelOffset(R.dimen.folder_rounded_corner_radius);
        }
    }

    public void bind(final Conversation conversation, final ControllableActivity activity,
            final ConversationSelectionSet set, final Folder folder,
            final int checkboxOrSenderImage,
            final boolean swipeEnabled, final boolean importanceMarkersEnabled,
            final boolean showChevronsEnabled, final AnimatedAdapter adapter) {
        Utils.traceBeginSection("CIVC.bind");
        bind(ConversationItemViewModel.forConversation(mAccount, conversation), activity,
                null /* conversationItemAreaClickListener */,
                set, folder, checkboxOrSenderImage, swipeEnabled, importanceMarkersEnabled,
                showChevronsEnabled, adapter, -1 /* backgroundOverrideResId */,
                null /* photoBitmap */, false /* useFullMargins */);
        Utils.traceEndSection();
    }

    public void bindAd(final ConversationItemViewModel conversationItemViewModel,
            final ControllableActivity activity,
            final ConversationItemAreaClickListener conversationItemAreaClickListener,
            final Folder folder, final int checkboxOrSenderImage, final AnimatedAdapter adapter,
            final int backgroundOverrideResId, final Bitmap photoBitmap) {
        Utils.traceBeginSection("CIVC.bindAd");
        bind(conversationItemViewModel, activity, conversationItemAreaClickListener, null /* set */,
                folder, checkboxOrSenderImage, true /* swipeEnabled */,
                false /* importanceMarkersEnabled */, false /* showChevronsEnabled */,
                adapter, backgroundOverrideResId, photoBitmap, true /* useFullMargins */);
        Utils.traceEndSection();
    }

    private void bind(final ConversationItemViewModel header, final ControllableActivity activity,
            final ConversationItemAreaClickListener conversationItemAreaClickListener,
            final ConversationSelectionSet set, final Folder folder,
            final int checkboxOrSenderImage,
            boolean swipeEnabled, final boolean importanceMarkersEnabled,
            final boolean showChevronsEnabled, final AnimatedAdapter adapter,
            final int backgroundOverrideResId, final Bitmap photoBitmap,
            final boolean useFullMargins) {
        mBackgroundOverrideResId = backgroundOverrideResId;
        mPhotoBitmap = photoBitmap;
        mConversationItemAreaClickListener = conversationItemAreaClickListener;

        if (mHeader != null) {
            Utils.traceBeginSection("unbind");
            final boolean newlyBound = header.conversation.id != mHeader.conversation.id;
            // If this was previously bound to a different conversation, remove any contact photo
            // manager requests.
            if (newlyBound || (mHeader.displayableNames != null && !mHeader
                    .displayableNames.equals(header.displayableNames))) {
                mSendersImageView.getContactDrawable().unbind();
            }

            if (newlyBound) {
                // Stop the photo flip animation
                final boolean showSenders = !isSelected();
                mSendersImageView.reset(showSenders);
            }
            Utils.traceEndSection();
        }
        mCoordinates = null;
        mHeader = header;
        mActivity = activity;
        mSelectedConversationSet = set;
        if (mSelectedConversationSet != null) {
            mSelectedConversationSet.addObserver(this);
        }
        mDisplayedFolder = folder;
        mStarEnabled = folder != null && !folder.isTrash();
        mSwipeEnabled = swipeEnabled;
        mAdapter = adapter;

        Utils.traceBeginSection("drawables");
        mSendersImageView.getContactDrawable().setBitmapCache(mAdapter.getSendersImagesCache());
        mSendersImageView.getContactDrawable().setContactResolver(mAdapter.getContactResolver());
        Utils.traceEndSection();

        if (checkboxOrSenderImage == ConversationListIcon.SENDER_IMAGE) {
            mGadgetMode = ConversationItemViewCoordinates.GADGET_CONTACT_PHOTO;
        } else {
            mGadgetMode = ConversationItemViewCoordinates.GADGET_NONE;
        }

        Utils.traceBeginSection("folder displayer");
        // Initialize folder displayer.
        if (mHeader.folderDisplayer == null) {
            mHeader.folderDisplayer = new ConversationItemFolderDisplayer(mContext);
        } else {
            mHeader.folderDisplayer.reset();
        }
        Utils.traceEndSection();

        final int ignoreFolderType;
        if (mDisplayedFolder.isInbox()) {
            ignoreFolderType = FolderType.INBOX;
        } else {
            ignoreFolderType = -1;
        }

        Utils.traceBeginSection("load folders");
        mHeader.folderDisplayer.loadConversationFolders(mHeader.conversation,
                mDisplayedFolder.folderUri, ignoreFolderType);
        Utils.traceEndSection();

        if (mHeader.showDateText) {
            Utils.traceBeginSection("relative time");
            mHeader.dateText = DateUtils.getRelativeTimeSpanString(mContext,
                    mHeader.conversation.dateMs);
            Utils.traceEndSection();
        } else {
            mHeader.dateText = "";
        }

        Utils.traceBeginSection("config setup");
        mConfig = new ConversationItemViewCoordinates.Config()
            .withGadget(mGadgetMode)
            .setUseFullMargins(useFullMargins);
        if (header.folderDisplayer.hasVisibleFolders()) {
            mConfig.showFolders();
        }
        if (header.hasBeenForwarded || header.hasBeenRepliedTo || header.isInvite) {
            mConfig.showReplyState();
        }
        if (mHeader.conversation.color != 0) {
            mConfig.showColorBlock();
        }

        // Importance markers and chevrons (personal level indicators).
        mHeader.personalLevelBitmap = null;
        final int personalLevel = mHeader.conversation.personalLevel;
        final boolean isImportant =
                mHeader.conversation.priority == UIProvider.ConversationPriority.IMPORTANT;
        final boolean useImportantMarkers = isImportant && importanceMarkersEnabled;
        if (showChevronsEnabled &&
                personalLevel == UIProvider.ConversationPersonalLevel.ONLY_TO_ME) {
            mHeader.personalLevelBitmap = useImportantMarkers ? IMPORTANT_ONLY_TO_ME
                    : ONLY_TO_ME;
        } else if (showChevronsEnabled &&
                personalLevel == UIProvider.ConversationPersonalLevel.TO_ME_AND_OTHERS) {
            mHeader.personalLevelBitmap = useImportantMarkers ? IMPORTANT_TO_ME_AND_OTHERS
                    : TO_ME_AND_OTHERS;
        } else if (useImportantMarkers) {
            mHeader.personalLevelBitmap = IMPORTANT;
        }
        if (mHeader.personalLevelBitmap != null) {
            mConfig.showPersonalIndicator();
        }
        Utils.traceEndSection();

        Utils.traceBeginSection("content description");
        setContentDescription();
        Utils.traceEndSection();
        requestLayout();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mSelectedConversationSet != null) {
            mSelectedConversationSet.removeObserver(this);
        }
    }

    @Override
    public void invalidateDrawable(final Drawable who) {
        boolean handled = false;
        if (mCoordinates != null) {
            if (mSendersImageView.equals(who)) {
                final Rect r = new Rect(who.getBounds());
                r.offset(mCoordinates.contactImagesX, mCoordinates.contactImagesY);
                ConversationItemView.this.invalidate(r.left, r.top, r.right, r.bottom);
                handled = true;
            }
        }
        if (!handled) {
            super.invalidateDrawable(who);
        }
    }

    /**
     * Get the Conversation object associated with this view.
     */
    public Conversation getConversation() {
        return mHeader.conversation;
    }

    private static void startTimer(String tag) {
        if (sTimer != null) {
            sTimer.start(tag);
        }
    }

    private static void pauseTimer(String tag) {
        if (sTimer != null) {
            sTimer.pause(tag);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Utils.traceBeginSection("CIVC.measure");
        final int wSize = MeasureSpec.getSize(widthMeasureSpec);

        final int currentMode = mActivity.getViewMode().getMode();
        if (wSize != mViewWidth || mPreviousMode != currentMode) {
            mViewWidth = wSize;
            mPreviousMode = currentMode;
        }
        mHeader.viewWidth = mViewWidth;

        mConfig.updateWidth(wSize).setViewMode(currentMode)
                .setLayoutDirection(ViewCompat.getLayoutDirection(this));

        Resources res = getResources();
        mHeader.standardScaledDimen = res.getDimensionPixelOffset(R.dimen.standard_scaled_dimen);

        mCoordinates = ConversationItemViewCoordinates.forConfig(mContext, mConfig,
                mAdapter.getCoordinatesCache());

        if (mPhotoBitmap != null) {
            mPhotoRect = new Rect(0, 0, mCoordinates.contactImagesWidth,
                    mCoordinates.contactImagesHeight);
        }

        final int h = (mAnimatedHeightFraction != 1.0f) ?
                Math.round(mAnimatedHeightFraction * mCoordinates.height) : mCoordinates.height;
        setMeasuredDimension(mConfig.getWidth(), h);
        Utils.traceEndSection();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        startTimer(PERF_TAG_LAYOUT);
        Utils.traceBeginSection("CIVC.layout");

        super.onLayout(changed, left, top, right, bottom);

        Utils.traceBeginSection("text and bitmaps");
        calculateTextsAndBitmaps();
        Utils.traceEndSection();

        Utils.traceBeginSection("coordinates");
        calculateCoordinates();
        Utils.traceEndSection();

        // Subject.
        Utils.traceBeginSection("subject");
        createSubject(mHeader.unread);

        createSnippet();

        if (!mHeader.isLayoutValid()) {
            setContentDescription();
        }
        mHeader.validate();
        Utils.traceEndSection();

        pauseTimer(PERF_TAG_LAYOUT);
        if (sTimer != null && ++sLayoutCount >= PERF_LAYOUT_ITERATIONS) {
            sTimer.dumpResults();
            sTimer = new Timer();
            sLayoutCount = 0;
        }
        Utils.traceEndSection();
    }

    private void setContentDescription() {
        if (mActivity.isAccessibilityEnabled()) {
            mHeader.resetContentDescription();
            setContentDescription(
                    mHeader.getContentDescription(mContext, mDisplayedFolder.shouldShowRecipients()));
        }
    }

    @Override
    public void setBackgroundResource(int resourceId) {
        Utils.traceBeginSection("set background resource");
        Drawable drawable = mBackgrounds.get(resourceId);
        if (drawable == null) {
            drawable = getResources().getDrawable(resourceId);
            final int insetPadding = mHeader.insetPadding;
            if (insetPadding > 0) {
                drawable = new InsetDrawable(drawable, insetPadding);
            }
            mBackgrounds.put(resourceId, drawable);
        }
        if (getBackground() != drawable) {
            super.setBackgroundDrawable(drawable);
        }
        Utils.traceEndSection();
    }

    private void calculateTextsAndBitmaps() {
        startTimer(PERF_TAG_CALCULATE_TEXTS_BITMAPS);

        if (mSelectedConversationSet != null) {
            mSelected = mSelectedConversationSet.contains(mHeader.conversation);
        }
        setSelected(mSelected);
        mHeader.gadgetMode = mGadgetMode;

        updateBackground();

        mHeader.sendersDisplayText = new SpannableStringBuilder();

        mHeader.hasDraftMessage = mHeader.conversation.numDrafts() > 0;

        // Parse senders fragments.
        if (mHeader.preserveSendersText) {
            // This is a special view that doesn't need special sender formatting
            mHeader.sendersDisplayText = new SpannableStringBuilder(mHeader.sendersText);
            loadImages();
        } else if (mHeader.conversation.conversationInfo != null) {
            Context context = getContext();
            mHeader.messageInfoString = SendersView
                    .createMessageInfo(context, mHeader.conversation, true);
            int maxChars = ConversationItemViewCoordinates.getSendersLength(context,
                    mCoordinates.getMode(), mHeader.conversation.hasAttachments);
            mHeader.displayableEmails = new ArrayList<String>();
            mHeader.displayableNames = new ArrayList<String>();
            mHeader.styledNames = new ArrayList<SpannableString>();

            SendersView.format(context, mHeader.conversation.conversationInfo,
                    mHeader.messageInfoString.toString(), maxChars, mHeader.styledNames,
                    mHeader.displayableNames, mHeader.displayableEmails, mAccount,
                    mDisplayedFolder.shouldShowRecipients(), true);

            if (mHeader.displayableEmails.isEmpty() && mHeader.hasDraftMessage) {
                mHeader.displayableEmails.add(mAccount);
                mHeader.displayableNames.add(mAccount);
            }

            // If we have displayable senders, load their thumbnails
            loadImages();
        } else {
            LogUtils.wtf(LOG_TAG, "Null conversationInfo");
        }

        if (mHeader.isLayoutValid()) {
            pauseTimer(PERF_TAG_CALCULATE_TEXTS_BITMAPS);
            return;
        }
        startTimer(PERF_TAG_CALCULATE_FOLDERS);


        pauseTimer(PERF_TAG_CALCULATE_FOLDERS);

        // Paper clip icon.
        mHeader.paperclip = null;
        if (mHeader.conversation.hasAttachments) {
            mHeader.paperclip = ATTACHMENT;
        }

        startTimer(PERF_TAG_CALCULATE_SENDER_SUBJECT);

        pauseTimer(PERF_TAG_CALCULATE_SENDER_SUBJECT);
        pauseTimer(PERF_TAG_CALCULATE_TEXTS_BITMAPS);
    }

    // FIXME(ath): maybe move this to bind(). the only dependency on layout is on tile W/H, which
    // is immutable.
    private void loadImages() {
        if (mGadgetMode != ConversationItemViewCoordinates.GADGET_CONTACT_PHOTO
                || mHeader.displayableEmails == null
                || mHeader.displayableEmails.isEmpty()) {
            return;
        }
        if (mCoordinates.contactImagesWidth <= 0 || mCoordinates.contactImagesHeight <= 0) {
            LogUtils.w(LOG_TAG,
                    "Contact image width(%d) or height(%d) is 0 for mode: (%d).",
                    mCoordinates.contactImagesWidth, mCoordinates.contactImagesHeight,
                    mCoordinates.getMode());
            return;
        }

        mSendersImageView
                .setBounds(0, 0, mCoordinates.contactImagesWidth, mCoordinates.contactImagesHeight);

        Utils.traceBeginSection("load sender image");
        final ContactDrawable drawable = mSendersImageView.getContactDrawable();
        drawable.setDecodeDimensions(mCoordinates.contactImagesWidth,
                mCoordinates.contactImagesHeight);
        drawable.bind(mHeader.displayableNames.get(0), mHeader.displayableEmails.get(0));
        Utils.traceEndSection();
    }

    private static int makeExactSpecForSize(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    private static void layoutViewExactly(View v, int w, int h) {
        v.measure(makeExactSpecForSize(w), makeExactSpecForSize(h));
        v.layout(0, 0, w, h);
    }

    private void layoutParticipantText(SpannableStringBuilder participantText) {
        if (participantText != null) {
            if (isActivated() && showActivatedText()) {
                participantText.setSpan(sActivatedTextSpan, 0,
                        mHeader.styledMessageInfoStringOffset, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                participantText.removeSpan(sActivatedTextSpan);
            }

            final int w = mSendersWidth;
            final int h = mCoordinates.sendersHeight;
            mSendersTextView.setLayoutParams(new ViewGroup.LayoutParams(w, h));
            mSendersTextView.setMaxLines(mCoordinates.sendersLineCount);
            mSendersTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, mCoordinates.sendersFontSize);
            layoutViewExactly(mSendersTextView, w, h);

            mSendersTextView.setText(participantText);
        }
    }

    private void createSubject(final boolean isUnread) {
        final String badgeText = mHeader.badgeText == null ? "" : mHeader.badgeText;
        String subject = filterTag(getContext(), mHeader.conversation.subject);
        subject = Conversation.getSubjectForDisplay(mContext, badgeText, subject);
        final Spannable displayedStringBuilder = new SpannableString(subject);

        // since spans affect text metrics, add spans to the string before measure/layout or fancy
        // ellipsizing

        final int badgeTextLength = formatBadgeText(displayedStringBuilder, badgeText);

        if (!TextUtils.isEmpty(subject)) {
            displayedStringBuilder.setSpan(TextAppearanceSpan.wrap(
                    isUnread ? sSubjectTextUnreadSpan : sSubjectTextReadSpan),
                    badgeTextLength, subject.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (isActivated() && showActivatedText()) {
            displayedStringBuilder.setSpan(sActivatedTextSpan, badgeTextLength,
                    displayedStringBuilder.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        }

        final int subjectWidth = mCoordinates.subjectWidth;
        final int subjectHeight = mCoordinates.subjectHeight;
        mSubjectTextView.setLayoutParams(new ViewGroup.LayoutParams(subjectWidth, subjectHeight));
        mSubjectTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, mCoordinates.subjectFontSize);
        layoutViewExactly(mSubjectTextView, subjectWidth, subjectHeight);

        mSubjectTextView.setText(displayedStringBuilder);
    }

    private void createSnippet() {
        final String snippet = mHeader.conversation.getSnippet();
        final Spannable displayedStringBuilder = new SpannableString(snippet);

        if (!TextUtils.isEmpty(snippet)) {
            // Start after the end of the subject text; since the subject may be
            // "" or null, this could start at the 0th character in the subjectText string
            displayedStringBuilder.setSpan(ForegroundColorSpan.wrap(sSnippetTextSpan), 0,
                    displayedStringBuilder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // measure the width of the folders which overlap the snippet view
        final int availableFolderSpace = mCoordinates.foldersRight - mCoordinates.foldersLeft;
        final int folderWidth = mHeader.folderDisplayer.measureFolders(availableFolderSpace,
                mCoordinates.getFolderCellWidth());

        // size the snippet view by subtracting the folder width from the maximum snippet width
        final int snippetWidth = mCoordinates.maxSnippetWidth - folderWidth;
        final int snippetHeight = mCoordinates.snippetHeight;
        mSnippetTextView.setLayoutParams(new ViewGroup.LayoutParams(snippetWidth, snippetHeight));
        mSnippetTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, mCoordinates.snippetFontSize);
        layoutViewExactly(mSnippetTextView, snippetWidth, snippetHeight);

        mSnippetTextView.setText(displayedStringBuilder);
    }

    private int formatBadgeText(Spannable displayedStringBuilder, String badgeText) {
        final int badgeTextLength = (badgeText != null) ? badgeText.length() : 0;
        if (!TextUtils.isEmpty(badgeText)) {
            displayedStringBuilder.setSpan(TextAppearanceSpan.wrap(sBadgeTextSpan),
                    0, badgeTextLength, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            displayedStringBuilder.setSpan(TextAppearanceSpan.wrap(sBadgeBackgroundSpan),
                    0, badgeTextLength, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            displayedStringBuilder.setSpan(new BadgeSpan(displayedStringBuilder, this),
                    0, badgeTextLength, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return badgeTextLength;
    }

    // START BadgeSpan.BadgeSpanDimensions override

    @Override
    public int getHorizontalPadding() {
        return sBadgePaddingExtraWidth;
    }

    @Override
    public float getRoundedCornerRadius() {
        return sBadgeRoundedCornerRadius;
    }

    // END BadgeSpan.BadgeSpanDimensions override

    private boolean showActivatedText() {
        // For activated elements in tablet in conversation mode, we show an activated color, since
        // the background is dark blue for activated versus gray for non-activated.
        return mTabletDevice && !mListCollapsible;
    }

    private void calculateCoordinates() {
        startTimer(PERF_TAG_CALCULATE_COORDINATES);

        sPaint.setTextSize(mCoordinates.dateFontSize);
        sPaint.setTypeface(Typeface.DEFAULT);

        final boolean isRtl = ViewUtils.isViewRtl(this);

        mDateWidth = (int) sPaint.measureText(
                mHeader.dateText != null ? mHeader.dateText.toString() : "");
        if (mHeader.infoIcon != null) {
            mInfoIconX = (isRtl) ? mCoordinates.infoIconX :
                    mCoordinates.infoIconXRight - mHeader.infoIcon.getWidth();

            // If we have an info icon, we start drawing the date text:
            // At the end of the date TextView minus the width of the date text
            // In RTL mode, we just use dateX
            mDateX = (isRtl) ? mCoordinates.dateX : mCoordinates.dateXRight - mDateWidth;
        } else {
            // If there is no info icon, we start drawing the date text:
            // At the end of the info icon ImageView minus the width of the date text
            // We use the info icon ImageView for positioning, since we want the date text to be
            // at the right, since there is no info icon
            // In RTL, we just use infoIconX
            mDateX = (isRtl) ? mCoordinates.infoIconX : mCoordinates.infoIconXRight - mDateWidth;
        }

        // The paperclip is drawn starting at the start of the date text minus
        // the width of the paperclip and the date padding.
        // In RTL mode, it is at the end of the date (mDateX + mDateWidth) plus the
        // start date padding.
        mPaperclipX = (isRtl) ? mDateX + mDateWidth + mCoordinates.datePaddingStart :
                mDateX - ATTACHMENT.getWidth() - mCoordinates.datePaddingStart;

        // In normal mode, the senders x and width is based
        // on where the date/attachment icon start.
        final int dateAttachmentStart;
        // Have this end near the paperclip or date, not the folders.
        if (mHeader.paperclip != null) {
            // If there is a paperclip, the date/attachment start is at the start
            // of the paperclip minus the paperclip padding.
            // In RTL, it is at the end of the paperclip plus the paperclip padding.
            dateAttachmentStart = (isRtl) ?
                    mPaperclipX + ATTACHMENT.getWidth() + mCoordinates.paperclipPaddingStart
                    : mPaperclipX - mCoordinates.paperclipPaddingStart;
        } else {
            // If no paperclip, just use the start of the date minus the date padding start.
            // In RTL mode, this is just the paperclipX.
            dateAttachmentStart = (isRtl) ?
                    mPaperclipX : mDateX - mCoordinates.datePaddingStart;
        }
        // Senders width is the dateAttachmentStart - sendersX.
        // In RTL, it is sendersWidth + sendersX - dateAttachmentStart.
        mSendersWidth = (isRtl) ?
                mCoordinates.sendersWidth + mCoordinates.sendersX - dateAttachmentStart
                : dateAttachmentStart - mCoordinates.sendersX;
        mSendersX = (isRtl) ? dateAttachmentStart : mCoordinates.sendersX;

        // Second pass to layout each fragment.
        sPaint.setTextSize(mCoordinates.sendersFontSize);
        sPaint.setTypeface(Typeface.DEFAULT);

        if (mHeader.styledNames != null) {
            final SpannableStringBuilder participantText = elideParticipants(mHeader.styledNames);
            layoutParticipantText(participantText);
        } else {
            // First pass to calculate width of each fragment.
            if (mSendersWidth < 0) {
                mSendersWidth = 0;
            }

            mHeader.sendersDisplayLayout = new StaticLayout(mHeader.sendersDisplayText, sPaint,
                    mSendersWidth, Alignment.ALIGN_NORMAL, 1, 0, true);
        }

        if (mSendersWidth < 0) {
            mSendersWidth = 0;
        }

        pauseTimer(PERF_TAG_CALCULATE_COORDINATES);
    }

    // The rules for displaying elided participants are as follows:
    // 1) If there is message info (either a COUNT or DRAFT info to display), it MUST be shown
    // 2) If senders do not fit, ellipsize the last one that does fit, and stop
    // appending new senders
    SpannableStringBuilder elideParticipants(List<SpannableString> parts) {
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        float totalWidth = 0;
        boolean ellipsize = false;
        float width;
        boolean skipToHeader = false;

        // start with "To: " if we're showing recipients
        if (mDisplayedFolder.shouldShowRecipients() && !parts.isEmpty()) {
            final SpannableString toHeader = SendersView.getFormattedToHeader();
            CharacterStyle[] spans = toHeader.getSpans(0, toHeader.length(),
                    CharacterStyle.class);
            // There is only 1 character style span; make sure we apply all the
            // styles to the paint object before measuring.
            if (spans.length > 0) {
                spans[0].updateDrawState(sPaint);
            }
            totalWidth += sPaint.measureText(toHeader.toString());
            builder.append(toHeader);
            skipToHeader = true;
        }

        final SpannableStringBuilder messageInfoString = mHeader.messageInfoString;
        if (messageInfoString.length() > 0) {
            CharacterStyle[] spans = messageInfoString.getSpans(0, messageInfoString.length(),
                    CharacterStyle.class);
            // There is only 1 character style span; make sure we apply all the
            // styles to the paint object before measuring.
            if (spans.length > 0) {
                spans[0].updateDrawState(sPaint);
            }
            // Paint the message info string to see if we lose space.
            float messageInfoWidth = sPaint.measureText(messageInfoString.toString());
            totalWidth += messageInfoWidth;
        }
       SpannableString prevSender = null;
       SpannableString ellipsizedText;
        for (SpannableString sender : parts) {
            // There may be null sender strings if there were dupes we had to remove.
            if (sender == null) {
                continue;
            }
            // No more width available, we'll only show fixed fragments.
            if (ellipsize) {
                break;
            }
            CharacterStyle[] spans = sender.getSpans(0, sender.length(), CharacterStyle.class);
            // There is only 1 character style span.
            if (spans.length > 0) {
                spans[0].updateDrawState(sPaint);
            }
            // If there are already senders present in this string, we need to
            // make sure we prepend the dividing token
            if (SendersView.sElidedString.equals(sender.toString())) {
                prevSender = sender;
                sender = copyStyles(spans, sElidedPaddingToken + sender + sElidedPaddingToken);
            } else if (!skipToHeader && builder.length() > 0
                    && (prevSender == null || !SendersView.sElidedString.equals(prevSender
                            .toString()))) {
                prevSender = sender;
                sender = copyStyles(spans, sSendersSplitToken + sender);
            } else {
                prevSender = sender;
                skipToHeader = false;
            }
            if (spans.length > 0) {
                spans[0].updateDrawState(sPaint);
            }
            // Measure the width of the current sender and make sure we have space
            width = (int) sPaint.measureText(sender.toString());
            if (width + totalWidth > mSendersWidth) {
                // The text is too long, new line won't help. We have to
                // ellipsize text.
                ellipsize = true;
                width = mSendersWidth - totalWidth; // ellipsis width?
                ellipsizedText = copyStyles(spans,
                        TextUtils.ellipsize(sender, sPaint, width, TruncateAt.END));
                width = (int) sPaint.measureText(ellipsizedText.toString());
            } else {
                ellipsizedText = null;
            }
            totalWidth += width;

            final CharSequence fragmentDisplayText;
            if (ellipsizedText != null) {
                fragmentDisplayText = ellipsizedText;
            } else {
                fragmentDisplayText = sender;
            }
            builder.append(fragmentDisplayText);
        }
        mHeader.styledMessageInfoStringOffset = builder.length();
        builder.append(messageInfoString);
        return builder;
    }

    private static SpannableString copyStyles(CharacterStyle[] spans, CharSequence newText) {
        SpannableString s = new SpannableString(newText);
        if (spans != null && spans.length > 0) {
            s.setSpan(spans[0], 0, s.length(), 0);
        }
        return s;
    }

    /**
     * If the subject contains the tag of a mailing-list (text surrounded with
     * []), return the subject with that tag ellipsized, e.g.
     * "[android-gmail-team] Hello" -> "[andr...] Hello"
     */
    public static String filterTag(Context context, String subject) {
        String result = subject;
        String formatString = context.getResources().getString(R.string.filtered_tag);
        if (!TextUtils.isEmpty(subject) && subject.charAt(0) == '[') {
            int end = subject.indexOf(']');
            if (end > 0) {
                String tag = subject.substring(1, end);
                result = String.format(formatString, Utils.ellipsize(tag, 7),
                        subject.substring(end + 1));
            }
        }
        return result;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Utils.traceBeginSection("CIVC.draw");

        // Contact photo
        if (mGadgetMode == ConversationItemViewCoordinates.GADGET_CONTACT_PHOTO) {
            canvas.save();
            Utils.traceBeginSection("draw senders image");
            drawSendersImage(canvas);
            Utils.traceEndSection();
            canvas.restore();
        }

        // Senders.
        boolean isUnread = mHeader.unread;
        // Old style senders; apply text colors/ sizes/ styling.
        canvas.save();
        if (mHeader.sendersDisplayLayout != null) {
            sPaint.setTextSize(mCoordinates.sendersFontSize);
            sPaint.setTypeface(SendersView.getTypeface(isUnread));
            sPaint.setColor(sSendersTextColor);
            canvas.translate(mSendersX, mCoordinates.sendersY
                    + mHeader.sendersDisplayLayout.getTopPadding());
            mHeader.sendersDisplayLayout.draw(canvas);
        } else {
            drawSenders(canvas);
        }
        canvas.restore();


        // Subject.
        sPaint.setTypeface(Typeface.DEFAULT);
        canvas.save();
        drawSubject(canvas);
        canvas.restore();

        canvas.save();
        drawSnippet(canvas);
        canvas.restore();

        // Folders.
        if (mConfig.areFoldersVisible()) {
            mHeader.folderDisplayer.drawFolders(canvas, mCoordinates, ViewUtils.isViewRtl(this));
        }

        // If this folder has a color (combined view/Email), show it here
        if (mConfig.isColorBlockVisible()) {
            sFoldersPaint.setColor(mHeader.conversation.color);
            sFoldersPaint.setStyle(Paint.Style.FILL);
            canvas.drawRect(mCoordinates.colorBlockX, mCoordinates.colorBlockY,
                    mCoordinates.colorBlockX + mCoordinates.colorBlockWidth,
                    mCoordinates.colorBlockY + mCoordinates.colorBlockHeight, sFoldersPaint);
        }

        // Draw the reply state. Draw nothing if neither replied nor forwarded.
        if (mConfig.isReplyStateVisible()) {
            if (mHeader.hasBeenRepliedTo && mHeader.hasBeenForwarded) {
                canvas.drawBitmap(STATE_REPLIED_AND_FORWARDED, mCoordinates.replyStateX,
                        mCoordinates.replyStateY, null);
            } else if (mHeader.hasBeenRepliedTo) {
                canvas.drawBitmap(STATE_REPLIED, mCoordinates.replyStateX,
                        mCoordinates.replyStateY, null);
            } else if (mHeader.hasBeenForwarded) {
                canvas.drawBitmap(STATE_FORWARDED, mCoordinates.replyStateX,
                        mCoordinates.replyStateY, null);
            } else if (mHeader.isInvite) {
                canvas.drawBitmap(STATE_CALENDAR_INVITE, mCoordinates.replyStateX,
                        mCoordinates.replyStateY, null);
            }
        }

        if (mConfig.isPersonalIndicatorVisible()) {
            canvas.drawBitmap(mHeader.personalLevelBitmap, mCoordinates.personalIndicatorX,
                    mCoordinates.personalIndicatorY, null);
        }

        // Info icon
        if (mHeader.infoIcon != null) {
            canvas.drawBitmap(mHeader.infoIcon, mInfoIconX, mCoordinates.infoIconY, sPaint);
        }

        // Date.
        sPaint.setTextSize(mCoordinates.dateFontSize);
        sPaint.setTypeface(Typeface.DEFAULT);
        sPaint.setColor(isUnread ? sDateTextColorUnread : sDateTextColorRead);
        drawText(canvas, mHeader.dateText, mDateX, mCoordinates.dateYBaseline, sPaint);

        // Paper clip icon.
        if (mHeader.paperclip != null) {
            canvas.drawBitmap(mHeader.paperclip, mPaperclipX, mCoordinates.paperclipY, sPaint);
        }

        if (mStarEnabled) {
            // Star.
            canvas.drawBitmap(getStarBitmap(), mCoordinates.starX, mCoordinates.starY, sPaint);
        }

        // right-side edge effect when in tablet conversation mode and the list is not collapsed
        if (Utils.getDisplayListRightEdgeEffect(mTabletDevice, mListCollapsible,
                mConfig.getViewMode())) {
            final boolean isRtl = ViewUtils.isViewRtl(this);
            RIGHT_EDGE_TABLET.setBounds(
                    (isRtl) ? 0 : getWidth() - RIGHT_EDGE_TABLET.getIntrinsicWidth(), 0,
                    (isRtl) ? RIGHT_EDGE_TABLET.getIntrinsicWidth() : getWidth(), getHeight());
            RIGHT_EDGE_TABLET.draw(canvas);

            if (isActivated()) {
                // draw caret on the end, centered vertically
                final int x = (isRtl) ? 0 : getWidth() - VISIBLE_CONVERSATION_CARET.getWidth();
                final int y = (getHeight() - VISIBLE_CONVERSATION_CARET.getHeight()) / 2;
                if (isRtl) {
                    // draw the bitmap mirrored in RTL mode
                    canvas.save();
                    canvas.scale(-1, 1,
                            x + VISIBLE_CONVERSATION_CARET.getWidth()/2,
                            y + VISIBLE_CONVERSATION_CARET.getHeight()/2);
                    canvas.drawBitmap(VISIBLE_CONVERSATION_CARET, x, y, null);
                    canvas.restore();
                } else {
                    canvas.drawBitmap(VISIBLE_CONVERSATION_CARET, x, y, null);
                }
            }
        }
        Utils.traceEndSection();
    }

    private void drawSendersImage(final Canvas canvas) {
        if (!mSendersImageView.isFlipping()) {
            final boolean showSenders = !isSelected();
            mSendersImageView.reset(showSenders);
        }
        canvas.translate(mCoordinates.contactImagesX, mCoordinates.contactImagesY);
        if (mPhotoBitmap == null) {
            mSendersImageView.draw(canvas);
        } else {
            canvas.drawBitmap(mPhotoBitmap, null, mPhotoRect, sPaint);
        }
    }

    private void drawSubject(Canvas canvas) {
        canvas.translate(mCoordinates.subjectX, mCoordinates.subjectY);
        mSubjectTextView.draw(canvas);
    }

    private void drawSnippet(Canvas canvas) {
        // if folders exist, their width will be the max width - actual width
        final int folderWidth = mCoordinates.maxSnippetWidth - mSnippetTextView.getWidth();

        // in RTL layouts we move the snippet to the right so it doesn't overlap the folders
        final int x = mCoordinates.snippetX + (ViewUtils.isViewRtl(this) ? folderWidth : 0);
        canvas.translate(x, mCoordinates.snippetY);
        mSnippetTextView.draw(canvas);
    }

    private void drawSenders(Canvas canvas) {
        canvas.translate(mSendersX, mCoordinates.sendersY);
        mSendersTextView.draw(canvas);
    }

    private Bitmap getStarBitmap() {
        return mHeader.conversation.starred ? STAR_ON : STAR_OFF;
    }

    private static void drawText(Canvas canvas, CharSequence s, int x, int y, TextPaint paint) {
        canvas.drawText(s, 0, s.length(), x, y, paint);
    }

    /**
     * Set the background for this item based on:
     * 1. Read / Unread (unread messages have a lighter background)
     * 2. Tablet / Phone
     * 3. Checkbox checked / Unchecked (controls CAB color for item)
     * 4. Activated / Not activated (controls the blue highlight on tablet)
     */
    private void updateBackground() {
        final int background;
        if (mBackgroundOverrideResId > 0) {
            background = mBackgroundOverrideResId;
        } else if (mHeader.unread) {
            background = R.drawable.conversation_unread_selector;
        } else {
            background = R.drawable.conversation_read_selector;
        }
        setBackgroundResource(background);
    }

    /**
     * Toggle the check mark on this view and update the conversation or begin
     * drag, if drag is enabled.
     */
    @Override
    public boolean toggleSelectedStateOrBeginDrag() {
        ViewMode mode = mActivity.getViewMode();
        if (mIsExpansiveTablet && mode.isListMode()) {
            return beginDragMode();
        } else {
            return toggleSelectedState("long_press");
        }
    }

    @Override
    public boolean toggleSelectedState() {
        return toggleSelectedState(null);
    }

    private boolean toggleSelectedState(final String sourceOpt) {
        if (mHeader != null && mHeader.conversation != null && mSelectedConversationSet != null) {
            mSelected = !mSelected;
            setSelected(mSelected);
            final Conversation conv = mHeader.conversation;
            // Set the list position of this item in the conversation
            final SwipeableListView listView = getListView();

            try {
                conv.position = mSelected && listView != null ? listView.getPositionForView(this)
                        : Conversation.NO_POSITION;
            } catch (final NullPointerException e) {
                // TODO(skennedy) Remove this if we find the root cause b/9527863
            }

            if (mSelectedConversationSet.isEmpty()) {
                final String source = (sourceOpt != null) ? sourceOpt : "checkbox";
                Analytics.getInstance().sendEvent("enter_cab_mode", source, null, 0);
            }

            mSelectedConversationSet.toggle(conv);
            if (mSelectedConversationSet.isEmpty()) {
                listView.commitDestructiveActions(true);
            }

            final boolean front = !mSelected;
            mSendersImageView.flipTo(front);

            // We update the background after the checked state has changed
            // now that we have a selected background asset. Setting the background
            // usually waits for a layout pass, but we don't need a full layout,
            // just an update to the background.
            requestLayout();

            return true;
        }

        return false;
    }

    @Override
    public void onSetEmpty() {
        mSendersImageView.flipTo(true);
    }

    @Override
    public void onSetPopulated(final ConversationSelectionSet set) { }

    @Override
    public void onSetChanged(final ConversationSelectionSet set) { }

    /**
     * Toggle the star on this view and update the conversation.
     */
    public void toggleStar() {
        mHeader.conversation.starred = !mHeader.conversation.starred;
        Bitmap starBitmap = getStarBitmap();
        postInvalidate(mCoordinates.starX, mCoordinates.starY, mCoordinates.starX
                + starBitmap.getWidth(),
                mCoordinates.starY + starBitmap.getHeight());
        ConversationCursor cursor = (ConversationCursor) mAdapter.getCursor();
        if (cursor != null) {
            // TODO(skennedy) What about ads?
            cursor.updateBoolean(mHeader.conversation, ConversationColumns.STARRED,
                    mHeader.conversation.starred);
        }
    }

    private boolean isTouchInContactPhoto(float x, float y) {
        // Everything before the end edge of contact photo

        final boolean isRtl = ViewUtils.isViewRtl(this);
        final int threshold = (isRtl) ? mCoordinates.contactImagesX - sSenderImageTouchSlop :
                mCoordinates.contactImagesX + mCoordinates.contactImagesWidth
                + sSenderImageTouchSlop;

        // Allow touching a little right of the contact photo when we're already in selection mode
        final float extra;
        if (mSelectedConversationSet == null || mSelectedConversationSet.isEmpty()) {
            extra = 0;
        } else {
            extra = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16,
                    getResources().getDisplayMetrics());
        }

        return mHeader.gadgetMode == ConversationItemViewCoordinates.GADGET_CONTACT_PHOTO
                && ((isRtl) ? x > (threshold - extra) : x < (threshold + extra));
    }

    private boolean isTouchInInfoIcon(final float x, final float y) {
        if (mHeader.infoIcon == null) {
            // We have no info icon
            return false;
        }

        final boolean isRtl = ViewUtils.isViewRtl(this);
        // Regardless of device, we always want to be end of the date's start touch slop
        if (((isRtl) ? x > mDateX + mDateWidth + sStarTouchSlop : x < mDateX - sStarTouchSlop)) {
            return false;
        }

        if (mStarEnabled) {
            // We allow touches all the way to the right edge, so no x check is necessary

            // We need to be above the star's touch area, which ends at the top of the subject
            // text
            return y < mCoordinates.subjectY;
        }

        // With no star below the info icon, we allow touches anywhere from the top edge to the
        // bottom edge
        return true;
    }

    private boolean isTouchInStar(float x, float y) {
        if (mHeader.infoIcon != null) {
            // We have an info icon, and it's above the star
            // We allow touches everywhere below the top of the subject text
            if (y < mCoordinates.subjectY) {
                return false;
            }
        }

        // Everything after the star and include a touch slop.
        return mStarEnabled && isTouchInStarTargetX(ViewUtils.isViewRtl(this), x);
    }

    private boolean isTouchInStarTargetX(boolean isRtl, float x) {
        return (isRtl) ? x < mCoordinates.starX + mCoordinates.starWidth + sStarTouchSlop
                : x >= mCoordinates.starX - sStarTouchSlop;
    }

    @Override
    public boolean canChildBeDismissed() {
        return true;
    }

    @Override
    public void dismiss() {
        SwipeableListView listView = getListView();
        if (listView != null) {
            listView.dismissChild(this);
        }
    }

    private boolean onTouchEventNoSwipe(MotionEvent event) {
        Utils.traceBeginSection("on touch event no swipe");
        boolean handled = false;

        int x = (int) event.getX();
        int y = (int) event.getY();
        mLastTouchX = x;
        mLastTouchY = y;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (isTouchInContactPhoto(x, y) || isTouchInInfoIcon(x, y) || isTouchInStar(x, y)) {
                    mDownEvent = true;
                    handled = true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                mDownEvent = false;
                break;

            case MotionEvent.ACTION_UP:
                if (mDownEvent) {
                    if (isTouchInContactPhoto(x, y)) {
                        // Touch on the check mark
                        toggleSelectedState();
                    } else if (isTouchInInfoIcon(x, y)) {
                        if (mConversationItemAreaClickListener != null) {
                            mConversationItemAreaClickListener.onInfoIconClicked();
                        }
                    } else if (isTouchInStar(x, y)) {
                        // Touch on the star
                        if (mConversationItemAreaClickListener == null) {
                            toggleStar();
                        } else {
                            mConversationItemAreaClickListener.onStarClicked();
                        }
                    }
                    handled = true;
                }
                break;
        }

        if (!handled) {
            handled = super.onTouchEvent(event);
        }

        Utils.traceEndSection();
        return handled;
    }

    /**
     * ConversationItemView is given the first chance to handle touch events.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Utils.traceBeginSection("on touch event");
        int x = (int) event.getX();
        int y = (int) event.getY();
        mLastTouchX = x;
        mLastTouchY = y;
        if (!mSwipeEnabled) {
            Utils.traceEndSection();
            return onTouchEventNoSwipe(event);
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (isTouchInContactPhoto(x, y) || isTouchInInfoIcon(x, y) || isTouchInStar(x, y)) {
                    mDownEvent = true;
                    Utils.traceEndSection();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mDownEvent) {
                    if (isTouchInContactPhoto(x, y)) {
                        // Touch on the check mark
                        Utils.traceEndSection();
                        mDownEvent = false;
                        toggleSelectedState();
                        Utils.traceEndSection();
                        return true;
                    } else if (isTouchInInfoIcon(x, y)) {
                        // Touch on the info icon
                        mDownEvent = false;
                        if (mConversationItemAreaClickListener != null) {
                            mConversationItemAreaClickListener.onInfoIconClicked();
                        }
                        Utils.traceEndSection();
                        return true;
                    } else if (isTouchInStar(x, y)) {
                        // Touch on the star
                        mDownEvent = false;
                        if (mConversationItemAreaClickListener == null) {
                            toggleStar();
                        } else {
                            mConversationItemAreaClickListener.onStarClicked();
                        }
                        Utils.traceEndSection();
                        return true;
                    }
                }
                break;
        }
        // Let View try to handle it as well.
        boolean handled = super.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            Utils.traceEndSection();
            return true;
        }
        Utils.traceEndSection();
        return handled;
    }

    @Override
    public boolean performClick() {
        final boolean handled = super.performClick();
        final SwipeableListView list = getListView();
        if (!handled && list != null && list.getAdapter() != null) {
            final int pos = list.findConversation(this, mHeader.conversation);
            list.performItemClick(this, pos, mHeader.conversation.id);
        }
        return handled;
    }

    private View unwrap() {
        final ViewParent vp = getParent();
        if (vp == null || !(vp instanceof View)) {
            return null;
        }
        return (View) vp;
    }

    private SwipeableListView getListView() {
        SwipeableListView v = null;
        final View wrapper = unwrap();
        if (wrapper != null && wrapper instanceof SwipeableConversationItemView) {
            v = (SwipeableListView) ((SwipeableConversationItemView) wrapper).getListView();
        }
        if (v == null) {
            v = mAdapter.getListView();
        }
        return v;
    }

    /**
     * Reset any state associated with this conversation item view so that it
     * can be reused.
     */
    public void reset() {
        Utils.traceBeginSection("reset");
        setAlpha(1f);
        setTranslationX(0f);
        mAnimatedHeightFraction = 1.0f;
        Utils.traceEndSection();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setTranslationX(float translationX) {
        super.setTranslationX(translationX);

        // When a list item is being swiped or animated, ensure that the hosting view has a
        // background color set. We only enable the background during the X-translation effect to
        // reduce overdraw during normal list scrolling.
        final View parent = (View) getParent();
        if (parent == null) {
            LogUtils.w(LOG_TAG, "CIV.setTranslationX null ConversationItemView parent x=%s",
                    translationX);
        }

        if (parent instanceof SwipeableConversationItemView) {
            if (translationX != 0f) {
                parent.setBackgroundResource(R.color.swiped_bg_color);
            } else {
                parent.setBackgroundDrawable(null);
            }
        }
    }

    /**
     * Grow the height of the item and fade it in when bringing a conversation
     * back from a destructive action.
     */
    public Animator createSwipeUndoAnimation() {
        ObjectAnimator undoAnimator = createTranslateXAnimation(true);
        return undoAnimator;
    }

    /**
     * Grow the height of the item and fade it in when bringing a conversation
     * back from a destructive action.
     */
    public Animator createUndoAnimation() {
        ObjectAnimator height = createHeightAnimation(true);
        Animator fade = ObjectAnimator.ofFloat(this, "alpha", 0, 1.0f);
        fade.setDuration(sShrinkAnimationDuration);
        fade.setInterpolator(new DecelerateInterpolator(2.0f));
        AnimatorSet transitionSet = new AnimatorSet();
        transitionSet.playTogether(height, fade);
        transitionSet.addListener(new HardwareLayerEnabler(this));
        return transitionSet;
    }

    /**
     * Grow the height of the item and fade it in when bringing a conversation
     * back from a destructive action.
     */
    public Animator createDestroyWithSwipeAnimation() {
        ObjectAnimator slide = createTranslateXAnimation(false);
        ObjectAnimator height = createHeightAnimation(false);
        AnimatorSet transitionSet = new AnimatorSet();
        transitionSet.playSequentially(slide, height);
        return transitionSet;
    }

    private ObjectAnimator createTranslateXAnimation(boolean show) {
        SwipeableListView parent = getListView();
        // If we can't get the parent...we have bigger problems.
        int width = parent != null ? parent.getMeasuredWidth() : 0;
        final float start = show ? width : 0f;
        final float end = show ? 0f : width;
        ObjectAnimator slide = ObjectAnimator.ofFloat(this, "translationX", start, end);
        slide.setInterpolator(new DecelerateInterpolator(2.0f));
        slide.setDuration(sSlideAnimationDuration);
        return slide;
    }

    public Animator createDestroyAnimation() {
        return createHeightAnimation(false);
    }

    private ObjectAnimator createHeightAnimation(boolean show) {
        final float start = show ? 0f : 1.0f;
        final float end = show ? 1.0f : 0f;
        ObjectAnimator height = ObjectAnimator.ofFloat(this, "animatedHeightFraction", start, end);
        height.setInterpolator(new DecelerateInterpolator(2.0f));
        height.setDuration(sShrinkAnimationDuration);
        return height;
    }

    // Used by animator
    public void setAnimatedHeightFraction(float height) {
        mAnimatedHeightFraction = height;
        requestLayout();
    }

    @Override
    public SwipeableView getSwipeableView() {
        return SwipeableView.from(this);
    }

    /**
     * Begin drag mode. Keep the conversation selected (NOT toggle selection) and start drag.
     */
    private boolean beginDragMode() {
        if (mLastTouchX < 0 || mLastTouchY < 0 ||  mSelectedConversationSet == null) {
            return false;
        }
        // If this is already checked, don't bother unchecking it!
        if (!mSelected) {
            toggleSelectedState();
        }

        // Clip data has form: [conversations_uri, conversationId1,
        // maxMessageId1, label1, conversationId2, maxMessageId2, label2, ...]
        final int count = mSelectedConversationSet.size();
        String description = Utils.formatPlural(mContext, R.plurals.move_conversation, count);

        final ClipData data = ClipData.newUri(mContext.getContentResolver(), description,
                Conversation.MOVE_CONVERSATIONS_URI);
        for (Conversation conversation : mSelectedConversationSet.values()) {
            data.addItem(new Item(String.valueOf(conversation.position)));
        }
        // Protect against non-existent views: only happens for monkeys
        final int width = this.getWidth();
        final int height = this.getHeight();
        final boolean isDimensionNegative = (width < 0) || (height < 0);
        if (isDimensionNegative) {
            LogUtils.e(LOG_TAG, "ConversationItemView: dimension is negative: "
                        + "width=%d, height=%d", width, height);
            return false;
        }
        mActivity.startDragMode();
        // Start drag mode
        startDrag(data, new ShadowBuilder(this, count, mLastTouchX, mLastTouchY), null, 0);

        return true;
    }

    /**
     * Handles the drag event.
     *
     * @param event the drag event to be handled
     */
    @Override
    public boolean onDragEvent(DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_ENDED:
                mActivity.stopDragMode();
                return true;
        }
        return false;
    }

    private class ShadowBuilder extends DragShadowBuilder {
        private final Drawable mBackground;

        private final View mView;
        private final String mDragDesc;
        private final int mTouchX;
        private final int mTouchY;
        private int mDragDescX;
        private int mDragDescY;

        public ShadowBuilder(View view, int count, int touchX, int touchY) {
            super(view);
            mView = view;
            mBackground = mView.getResources().getDrawable(R.drawable.list_pressed_holo);
            mDragDesc = Utils.formatPlural(mView.getContext(), R.plurals.move_conversation, count);
            mTouchX = touchX;
            mTouchY = touchY;
        }

        @Override
        public void onProvideShadowMetrics(Point shadowSize, Point shadowTouchPoint) {
            final int width = mView.getWidth();
            final int height = mView.getHeight();

            sPaint.setTextSize(mCoordinates.subjectFontSize);
            mDragDescX = mCoordinates.sendersX;
            mDragDescY = (height - (int) mCoordinates.subjectFontSize) / 2 ;
            shadowSize.set(width, height);
            shadowTouchPoint.set(mTouchX, mTouchY);
        }

        @Override
        public void onDrawShadow(Canvas canvas) {
            mBackground.setBounds(0, 0, mView.getWidth(), mView.getHeight());
            mBackground.draw(canvas);
            sPaint.setTextSize(mCoordinates.subjectFontSize);
            canvas.drawText(mDragDesc, mDragDescX, mDragDescY - sPaint.ascent(), sPaint);
        }
    }

    @Override
    public float getMinAllowScrollDistance() {
        return sScrollSlop;
    }

    public String getAccount() {
        return mAccount;
    }
}
