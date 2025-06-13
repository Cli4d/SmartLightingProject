package com.smart.smartbulb;



import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Circular Color Picker with draggable selector
 * Allows users to select colors by touching/dragging around a color wheel
 */
public class CircularColorPicker extends View {

    private Paint colorWheelPaint;
    private Paint centerPaint;
    private Paint selectorPaint;
    private Paint selectorBorderPaint;

    private RectF colorWheelRect;
    private float centerX, centerY;
    private float colorWheelRadius;
    private float selectorRadius = 20f;

    private float currentHue = 0f;
    private float currentSaturation = 1f;
    private int selectedColor = Color.RED;

    private OnColorSelectedListener colorSelectedListener;
    private boolean isDragging = false;

    public interface OnColorSelectedListener {
        void onColorSelected(int color, String hexColor);
    }

    public CircularColorPicker(Context context) {
        super(context);
        init();
    }

    public CircularColorPicker(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CircularColorPicker(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Initialize paints
        colorWheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectorBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Setup selector paint
        selectorPaint.setColor(Color.WHITE);
        selectorPaint.setStyle(Paint.Style.FILL);

        selectorBorderPaint.setColor(Color.BLACK);
        selectorBorderPaint.setStyle(Paint.Style.STROKE);
        selectorBorderPaint.setStrokeWidth(4f);

        // Setup center paint for brightness control
        centerPaint.setStyle(Paint.Style.FILL);

        setLayerType(LAYER_TYPE_SOFTWARE, null); // For gradient support
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        centerX = w / 2f;
        centerY = h / 2f;

        // Calculate wheel radius (leave space for selector)
        float minDimension = Math.min(w, h);
        colorWheelRadius = (minDimension / 2f) - selectorRadius - 20;

        // Setup color wheel rectangle
        colorWheelRect = new RectF(
                centerX - colorWheelRadius,
                centerY - colorWheelRadius,
                centerX + colorWheelRadius,
                centerY + colorWheelRadius
        );

        createColorWheelGradient();
        createCenterGradient();
    }

    private void createColorWheelGradient() {
        // Create sweep gradient for color wheel
        int[] colors = {
                Color.RED,          // 0°
                Color.YELLOW,       // 60°
                Color.GREEN,        // 120°
                Color.CYAN,         // 180°
                Color.BLUE,         // 240°
                Color.MAGENTA,      // 300°
                Color.RED           // 360°
        };

        SweepGradient sweepGradient = new SweepGradient(centerX, centerY, colors, null);
        colorWheelPaint.setShader(sweepGradient);
    }

    private void createCenterGradient() {
        // Create radial gradient for saturation (center to edge)
        int centerColor = Color.HSVToColor(new float[]{currentHue, 0f, 1f}); // White center
        int edgeColor = Color.HSVToColor(new float[]{currentHue, 1f, 1f});   // Full saturation edge

        RadialGradient radialGradient = new RadialGradient(
                centerX, centerY, colorWheelRadius * 0.7f,
                centerColor, edgeColor,
                Shader.TileMode.CLAMP
        );

        centerPaint.setShader(radialGradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw color wheel
        canvas.drawCircle(centerX, centerY, colorWheelRadius, colorWheelPaint);

        // Draw center saturation gradient
        canvas.drawCircle(centerX, centerY, colorWheelRadius * 0.7f, centerPaint);

        // Draw selector
        float[] selectorPosition = getSelectorPosition();
        canvas.drawCircle(selectorPosition[0], selectorPosition[1], selectorRadius, selectorPaint);
        canvas.drawCircle(selectorPosition[0], selectorPosition[1], selectorRadius, selectorBorderPaint);
    }

    private float[] getSelectorPosition() {
        double angle = Math.toRadians(currentHue);
        float radius = colorWheelRadius * currentSaturation * 0.7f;

        float x = centerX + (float) (radius * Math.cos(angle - Math.PI / 2));
        float y = centerY + (float) (radius * Math.sin(angle - Math.PI / 2));

        return new float[]{x, y};
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (isInsideColorWheel(x, y)) {
                    isDragging = true;
                    updateColorFromTouch(x, y);
                    return true;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    updateColorFromTouch(x, y);
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                break;
        }

        return super.onTouchEvent(event);
    }

    private boolean isInsideColorWheel(float x, float y) {
        float distance = (float) Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));
        return distance <= colorWheelRadius;
    }

    private void updateColorFromTouch(float x, float y) {
        float deltaX = x - centerX;
        float deltaY = y - centerY;

        // Calculate angle (hue)
        double angle = Math.atan2(deltaY, deltaX) + Math.PI / 2;
        if (angle < 0) angle += 2 * Math.PI;
        currentHue = (float) Math.toDegrees(angle);

        // Calculate distance from center (saturation)
        float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        float maxSaturationRadius = colorWheelRadius * 0.7f;
        currentSaturation = Math.min(distance / maxSaturationRadius, 1f);

        // Update selected color
        selectedColor = Color.HSVToColor(new float[]{currentHue, currentSaturation, 1f});

        // Update center gradient
        createCenterGradient();

        // Notify listener
        if (colorSelectedListener != null) {
            String hexColor = String.format("#%06X", (0xFFFFFF & selectedColor));
            colorSelectedListener.onColorSelected(selectedColor, hexColor);
        }

        invalidate();
    }

    /**
     * Set the current color programmatically
     */
    public void setColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        currentHue = hsv[0];
        currentSaturation = hsv[1];
        selectedColor = color;

        createCenterGradient();
        invalidate();
    }

    /**
     * Set the current color from hex string
     */
    public void setColor(String hexColor) {
        try {
            int color = Color.parseColor(hexColor);
            setColor(color);
        } catch (IllegalArgumentException e) {
            // Invalid color format, ignore
        }
    }

    /**
     * Get the currently selected color
     */
    public int getSelectedColor() {
        return selectedColor;
    }

    /**
     * Get the currently selected color as hex string
     */
    public String getSelectedColorHex() {
        return String.format("#%06X", (0xFFFFFF & selectedColor));
    }

    /**
     * Set the color selection listener
     */
    public void setOnColorSelectedListener(OnColorSelectedListener listener) {
        this.colorSelectedListener = listener;
    }

    /**
     * Set predefined colors for quick selection
     */
    public void selectPredefinedColor(String colorName) {
        switch (colorName.toLowerCase()) {
            case "warm white":
                setColor("#FFBB66");
                break;
            case "soft yellow":
                setColor("#FFDD99");
                break;
            case "pure white":
                setColor("#FFFFFF");
                break;
            case "cool white":
                setColor("#BBEEFF");
                break;
            case "soft blue":
                setColor("#99CCFF");
                break;
            case "lavender":
                setColor("#CC99FF");
                break;
            case "soft red":
                setColor("#FFAAAA");
                break;
            case "soft green":
                setColor("#AAFFAA");
                break;
            default:
                // Default to warm white
                setColor("#FFDD99");
                break;
        }
    }
}
