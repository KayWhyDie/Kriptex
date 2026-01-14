package com.ivor.kriptex.db;

import io.realm.RealmObject;
import io.realm.annotations.Index;
import io.realm.annotations.PrimaryKey;
import io.realm.annotations.Required;

public class ChatRoom extends RealmObject {

    @PrimaryKey
    @Required
    private String id;

    @Index
    private String name;

    @Index
    private long createdAt;

    // Last stableId the user has seen in this room (for unread indicators).
    @Index
    private long lastReadStableId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastReadStableId() {
        return lastReadStableId;
    }

    public void setLastReadStableId(long lastReadStableId) {
        this.lastReadStableId = lastReadStableId;
    }
}
