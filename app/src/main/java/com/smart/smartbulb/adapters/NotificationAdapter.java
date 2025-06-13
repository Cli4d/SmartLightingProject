package com.smart.smartbulb.adapters;

// NotificationAdapter.java

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.smart.smartbulb.R;
import com.smart.smartbulb.models.Notification;

import java.util.List;
import java.util.function.Consumer;

public class NotificationAdapter extends RecyclerView.Adapter<NotificationAdapter.NotificationViewHolder> {

    private final List<Notification> notifications;
    private final Consumer<Integer> dismissCallback;

    public NotificationAdapter(List<Notification> notifications, Consumer<Integer> dismissCallback) {
        this.notifications = notifications;
        this.dismissCallback = dismissCallback;
    }

    @NonNull
    @Override
    public NotificationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(
                R.layout.item_notification, parent, false);
        return new NotificationViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull NotificationViewHolder holder, int position) {
        Notification notification = notifications.get(position);
        holder.bind(notification);
    }

    @Override
    public int getItemCount() {
        return notifications.size();
    }

    class NotificationViewHolder extends RecyclerView.ViewHolder {
        private final TextView messageText;
        private final TextView timeText;
        private final ImageButton dismissButton;

        public NotificationViewHolder(@NonNull View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.textNotificationMessage);
            timeText = itemView.findViewById(R.id.textNotificationTime);
            dismissButton = itemView.findViewById(R.id.buttonDismiss);
        }

        public void bind(Notification notification) {
            messageText.setText(notification.getMessage());
            timeText.setText(notification.getTime());

            dismissButton.setOnClickListener(v -> {
                if (dismissCallback != null) {
                    dismissCallback.accept((int) notification.getId());
                }
            });
        }
    }
}