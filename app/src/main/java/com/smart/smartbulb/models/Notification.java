package com.smart.smartbulb.models;

// Notification.java

import java.io.Serializable;

public class Notification implements Serializable {
    private long id;
    private String message;
    private String time;

    public Notification() {
        // Default constructor
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}
