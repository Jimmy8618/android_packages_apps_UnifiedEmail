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

package com.android.mail.ui;

import android.app.Activity;
import android.app.Fragment;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import com.android.mail.FormattedDateBuilder;
import com.android.mail.R;
import com.android.mail.browse.MessageHeaderView;
import com.android.mail.browse.MessageWebView;
import com.android.mail.providers.Account;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;

/**
 * The conversation view UI component.
 */
public final class ConversationViewFragment extends Fragment {
    private static final String LOG_TAG = new LogUtils().getLogTag();

    private ControllableActivity mActivity;

    private ContentResolver mResolver;

    private Conversation mConversation;

    private TextView mSubject;

    private ListView mMessageList;

    private FormattedDateBuilder mDateBuilder;

    private Cursor mMessageCursor;

    private Account mAccount;
    /**
     * Hidden constructor.
     */
    private ConversationViewFragment(Account account, Conversation conversation) {
        super();
        mConversation = conversation;
        mAccount = account;
    }

    /**
     * Creates a new instance of {@link ConversationViewFragment}, initialized
     * to display conversation.
     */
    public static ConversationViewFragment newInstance(Account account, Conversation conversation) {
       return new ConversationViewFragment(account, conversation);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        // Strictly speaking, we get back an android.app.Activity from getActivity. However, the
        // only activity creating a ConversationListContext is a MailActivity which is of type
        // ControllableActivity, so this cast should be safe. If this cast fails, some other
        // activity is creating ConversationListFragments. This activity must be of type
        // ControllableActivity.
        final Activity activity = getActivity();
        if (! (activity instanceof ControllableActivity)){
            LogUtils.wtf(LOG_TAG, "ConversationViewFragment expects only a ControllableActivity to" +
                    "create it. Cannot proceed.");
        }
        mActivity = (ControllableActivity) activity;
        if (mActivity.isFinishing()) {
            // Activity is finishing, just bail.
            return;
        }
        mActivity.attachConversationView(this);
        mResolver = mActivity.getContentResolver();
        mDateBuilder = new FormattedDateBuilder(mActivity.getActivityContext());
        // Show conversation and start loading messages.
        showConversation();
    }

    @Override
    public void onCreate(Bundle savedState) {
        LogUtils.v(LOG_TAG, "onCreate in FolderListFragment(this=%s)", this);
        super.onCreate(savedState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
            ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.conversation_view, null);
        mSubject = (TextView) rootView.findViewById(R.id.subject);
        mMessageList = (ListView) rootView.findViewById(R.id.message_list);

        return rootView;
    }

    @Override
    public void onDestroyView() {
        // Clear the adapter.
        mMessageList.setAdapter(null);
        mActivity.attachConversationView(null);

        super.onDestroyView();
    }

    /**
     * Handles a request to show a new conversation list, either from a search query or for viewing
     * a label. This will initiate a data load, and hence must be called on the UI thread.
     */
    private void showConversation() {
        mSubject.setText(mConversation.subject);
        mMessageCursor = mResolver.query(mConversation.messageListUri,
                UIProvider.MESSAGE_PROJECTION, null, null, null);
        mMessageList.setAdapter(new MessageListAdapter(mActivity.getActivityContext(),
                mMessageCursor));
    }

    class MessageListAdapter extends SimpleCursorAdapter {
        public MessageListAdapter(Context context, Cursor cursor) {
            super(context, R.layout.message, cursor,
                    UIProvider.MESSAGE_PROJECTION, new int[0], 0);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            super.bindView(view, context, cursor);
            MessageHeaderView header = (MessageHeaderView) view.findViewById(R.id.message_header);
            header.initialize(mDateBuilder, mAccount, true, true, false);
            header.bind(cursor);
            MessageWebView webView = (MessageWebView) view.findViewById(R.id.body);
            webView.loadData(cursor.getString(UIProvider.MESSAGE_BODY_HTML_COLUMN), "text/html",
                    null);
        }
    }
}
