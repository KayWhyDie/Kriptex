package com.ivor.kriptex;

import android.os.Bundle;
import android.view.View;

import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ivor.kriptex.adapters.RequestsAdapter;
import com.ivor.kriptex.db.Contact;

import io.realm.Realm;
import io.realm.RealmResults;

public class RequestActivity extends AppCompatActivity {

    private RecyclerView mRVRequests;
    private RequestsAdapter mRequestsAdapter;

    private RealmResults<Contact> mContacts;
    private Realm mRealm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_request);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        mRVRequests = findViewById(R.id.rcvwRequests);

        mRealm = Realm.getDefaultInstance();
        mContacts = mRealm.where(Contact.class).notEqualTo("incoming", 0).findAll();
        mRVRequests.setLayoutManager(new LinearLayoutManager(this));
        mRequestsAdapter = new RequestsAdapter(mContacts, this);
        mRVRequests.setAdapter(mRequestsAdapter);
        mRVRequests.setHasFixedSize(true);
        mRVRequests.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        mContacts.addChangeListener((contacts, changeSet) -> {
            // This project uses a stub RealmRecyclerViewAdapter; autoUpdate is a no-op.
            // Explicitly refresh the RecyclerView when RealmResults change.
            mRequestsAdapter.notifyDataSetChanged();
            updateNoDataView();
        });
        updateNoDataView();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        findViewById(R.id.txtNoRequests).setVisibility(hasRequests ? View.GONE : View.VISIBLE);
        mRVRequests.setVisibility(hasRequests ? View.VISIBLE : View.GONE);
    }

}
