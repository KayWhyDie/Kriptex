package io.realm;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Minimal stub of the old Realm Android Adapters artifact.
 * This project uses Realm, but the adapter dependency is not present.
 */
public abstract class RealmRecyclerViewAdapter<T, VH extends RecyclerView.ViewHolder>
        extends RecyclerView.Adapter<VH> {

    @Nullable
    private OrderedRealmCollection<T> data;

    public RealmRecyclerViewAdapter(@Nullable OrderedRealmCollection<T> data, boolean autoUpdate) {
        this.data = data;
        // autoUpdate ignored in this stub
    }

    @Nullable
    public OrderedRealmCollection<T> getData() {
        return data;
    }

    public void updateData(@Nullable OrderedRealmCollection<T> data) {
        this.data = data;
        notifyDataSetChanged();
    }

    @Nullable
    public T getItem(int index) {
        if (data == null) return null;
        return data.get(index);
    }

    @Override
    public int getItemCount() {
        return data == null ? 0 : data.size();
    }
}
