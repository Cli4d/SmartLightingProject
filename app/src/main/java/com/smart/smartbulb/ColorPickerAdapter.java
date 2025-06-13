package com.smart.smartbulb;


import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

/**
 * Helper class for implementing the color selection in the Settings fragment.
 * This replaces the third-party MaterialDesignColorPicker with a native implementation.
 */
public class ColorPickerAdapter {

    public interface OnColorSelectedListener {
        void onColorSelected(String colorHex);
    }

    public static void setupColorPickers(ViewGroup colorContainer, String currentColor, OnColorSelectedListener listener) {
        // Find all color view items
        int childCount = colorContainer.getChildCount();

        // Track currently selected color
        View selectedView = null;

        for (int i = 0; i < childCount; i++) {
            View colorView = colorContainer.getChildAt(i);

            if (colorView.getTag() != null) {
                String colorHex = (String) colorView.getTag();

                // Set the background color based on the tag
                colorView.setBackgroundColor(android.graphics.Color.parseColor(colorHex));

                // Check if this is the currently selected color
                if (colorHex.equalsIgnoreCase(currentColor)) {
                    colorView.setSelected(true);
                    selectedView = colorView;
                }

                // Set click listener
                colorView.setOnClickListener(v -> {
                    // Clear selection on previously selected view
                    for (int j = 0; j < childCount; j++) {
                        View otherView = colorContainer.getChildAt(j);
                        otherView.setSelected(false);
                    }

                    // Set this view as selected
                    v.setSelected(true);

                    // Notify listener
                    listener.onColorSelected(colorHex);
                });
            }
        }

        // If no color was selected, select the default
        if (selectedView == null && childCount > 0) {
            View defaultView = colorContainer.getChildAt(1); // Yellow color
            defaultView.setSelected(true);
        }
    }
}