package com.smart.smartbulb.fragments;

// NotificationsFragment.java (without View Binding)

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.smart.smartbulb.R;
import com.smart.smartbulb.adapters.NotificationAdapter;
import com.smart.smartbulb.models.Notification;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class NotificationsFragment extends Fragment {
    private View rootView;
    private RecyclerView recyclerNotifications;
    private LinearLayout emptyView;

    private List<Notification> notifications;
    private NotificationAdapter adapter;
    private Consumer<Integer> dismissCallback;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_notifications, container, false);
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Find views
        recyclerNotifications = rootView.findViewById(R.id.recyclerNotifications);
        emptyView = rootView.findViewById(R.id.emptyView);

        // Get notifications from arguments
        if (getArguments() != null) {
            notifications = (ArrayList<Notification>) getArguments().getSerializable("notifications");
        }

        if (notifications == null) {
            notifications = new ArrayList<>();
        }

        // Set up recycler view
        setupRecyclerView();
    }

    public void setDismissCallback(Consumer<Integer> callback) {
        this.dismissCallback = callback;
    }

    private void setupRecyclerView() {
        adapter = new NotificationAdapter(notifications, notificationId -> {
            if (dismissCallback != null) {
                dismissCallback.accept(notificationId);
            }
        });

        recyclerNotifications.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerNotifications.setAdapter(adapter);

        // Show empty view if no notifications
        updateEmptyView();
    }

    public void refreshNotifications(List<Notification> updatedNotifications) {
        this.notifications.clear();
        this.notifications.addAll(updatedNotifications);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmptyView();
    }

    private void updateEmptyView() {
        if (notifications.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerNotifications.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerNotifications.setVisibility(View.VISIBLE);
        }
    }
}