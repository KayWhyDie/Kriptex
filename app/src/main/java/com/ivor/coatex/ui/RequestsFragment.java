package com.ivor.kriptex.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ivor.kriptex.R;
import com.ivor.kriptex.adapters.RequestsAdapter;
import com.ivor.kriptex.db.Contact;

import io.realm.Realm;
import io.realm.RealmResults;

public class RequestsFragment extends Fragment {

    private RecyclerView mRVRequests;
    private RequestsAdapter mRequestsAdapter;

    private View mainView;

    RealmResults<Contact> mContacts;
    private Realm mRealm;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        mainView = inflater.inflate(R.layout.fragment_requests, container, false);

        mRVRequests = mainView.findViewById(R.id.rcvwRequests);

        mRealm = Realm.getDefaultInstance();
        mContacts = mRealm.where(Contact.class).notEqualTo("incoming", 0).findAll();
        mRVRequests.setLayoutManager(new LinearLayoutManager(getContext()));
        mRequestsAdapter = new RequestsAdapter(mContacts, getContext());
        mRVRequests.setAdapter(mRequestsAdapter);
        mRVRequests.setHasFixedSize(true);
        mRVRequests.addItemDecoration(new DividerItemDecoration(mainView.getContext(), DividerItemDecoration.VERTICAL));

        mContacts.addChangeListener((contacts, changeSet) -> {
            // This project uses a stub RealmRecyclerViewAdapter; autoUpdate is a no-op.
            mRequestsAdapter.notifyDataSetChanged();
            updateNoDataView();
        });
        updateNoDataView();

        return mainView;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mContacts.removeAllChangeListeners();
        if (mRealm != null && !mRealm.isClosed()) {
            mRealm.close();
        }
    }

    private void updateNoDataView() {
        boolean hasRequests = mRequestsAdapter.getItemCount() > 0;
        mainView.findViewById(R.id.txtNoRequests).setVisibility(hasRequests ? View.GONE : View.VISIBLE);
        mRVRequests.setVisibility(hasRequests ? View.VISIBLE : View.GONE);
    }
}
