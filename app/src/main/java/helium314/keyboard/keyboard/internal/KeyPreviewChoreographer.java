/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import helium314.keyboard.keyboard.Key;
import helium314.keyboard.keyboard.KeyboardTheme;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.common.CoordinateUtils;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.utils.ViewLayoutUtils;

import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * This class controls pop up key previews. This class decides:
 * - what kind of key previews should be shown.
 * - where key previews should be placed.
 * - how key previews should be shown and dismissed.
 */
public final class KeyPreviewChoreographer {
    // Free {@link KeyPreviewView} pool that can be used for key preview.
    private final ArrayDeque<KeyPreviewView> mFreeKeyPreviewViews = new ArrayDeque<>();
    // Map from {@link Key} to {@link KeyPreviewView} that is currently being displayed as key
    // preview.
    private final HashMap<Key,KeyPreviewView> mShowingKeyPreviewViews = new HashMap<>();

    private final KeyPreviewDrawParams mParams;

    public KeyPreviewChoreographer(final KeyPreviewDrawParams params) {
        mParams = params;
    }

    public KeyPreviewView getKeyPreviewView(final Key key, final ViewGroup placerView) {
        KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.remove(key);
        if (keyPreviewView != null) {
            return keyPreviewView;
        }
        keyPreviewView = mFreeKeyPreviewViews.poll();
        if (keyPreviewView != null) {
            return keyPreviewView;
        }
        final Context context = placerView.getContext();
        keyPreviewView = new KeyPreviewView(context, null /* attrs */);
        if (isGlass()) { // Keyboard: the Glass callout, its shadow draws outside the view
            keyPreviewView.setBackground(GlassCalloutDrawable.forColors(context, Settings.getValues().mColors));
            keyPreviewView.setIncludeFontPadding(false);
            keyPreviewView.setMaxLines(1);
            placerView.setClipChildren(false);
        } else {
            keyPreviewView.setBackgroundResource(mParams.mPreviewBackgroundResId);
        }
        placerView.addView(keyPreviewView, ViewLayoutUtils.newLayoutParam(placerView, 0, 0));
        return keyPreviewView;
    }

    public boolean isShowingKeyPreview(final Key key) {
        return mShowingKeyPreviewViews.containsKey(key);
    }

    public void dismissKeyPreview(final Key key) {
        if (key == null) {
            return;
        }
        final KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.get(key);
        if (keyPreviewView == null) {
            return;
        }
        // Dismiss preview
        mShowingKeyPreviewViews.remove(key);
        keyPreviewView.setTag(null);
        keyPreviewView.setVisibility(View.INVISIBLE);
        mFreeKeyPreviewViews.add(keyPreviewView);
    }

    public void placeAndShowKeyPreview(final Key key, final KeyboardIconsSet iconsSet,
            final KeyDrawParams drawParams, final int fullKeyboardViewWidth, final int[] keyboardOrigin,
            final ViewGroup placerView) {
        // Keyboard: the Glass callout is for keys that type something, on a phone (InputCallout.isEnabled);
        // an icon, an empty label or a tablet gets none, the key's own pressed glass shows the press
        if (isGlass() && (!GlassCalloutDrawable.isEnabled(placerView.getContext()) || key.getIconName() != null
                || TextUtils.isEmpty(key.getPreviewLabel()))) {
            return;
        }
        final KeyPreviewView keyPreviewView = getKeyPreviewView(key, placerView);
        placeKeyPreview(key, keyPreviewView, iconsSet, drawParams, fullKeyboardViewWidth, keyboardOrigin);
        showKeyPreview(key, keyPreviewView);
    }

    private void placeKeyPreview(Key key, KeyPreviewView keyPreviewView, KeyboardIconsSet iconsSet,
            KeyDrawParams drawParams, int fullKeyboardViewWidth, int[] originCoords) {
        keyPreviewView.setPreviewVisual(key, iconsSet, drawParams);
        if (keyPreviewView.getBackground() instanceof GlassCalloutDrawable callout) {
            placeGlassCallout(key, keyPreviewView, callout, fullKeyboardViewWidth, originCoords);
            return;
        }
        keyPreviewView.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mParams.setGeometry(keyPreviewView);
        int previewWidth = keyPreviewView.getMeasuredWidth();
        int previewHeight = keyPreviewView.getMeasuredHeight();
        int keyDrawWidth = key.getDrawWidth();
        int originX = CoordinateUtils.x(originCoords);
        // The key preview is horizontally aligned with the center of the visible part of the
        // parent key. If it doesn't fit in this {@link KeyboardView}, it is moved inward to fit and
        // the left/right background is used if such background is specified.
        int keyPreviewPosition;
        int previewX = key.getDrawX() - (previewWidth - keyDrawWidth) / 2 + originX;
        if (previewX < originX) {
            previewX = originX;
            keyPreviewPosition = KeyPreviewView.POSITION_LEFT;
        } else if (previewX > fullKeyboardViewWidth - previewWidth + originX) {
            previewX = fullKeyboardViewWidth - previewWidth + originX;
            keyPreviewPosition = KeyPreviewView.POSITION_RIGHT;
        } else {
            keyPreviewPosition = KeyPreviewView.POSITION_MIDDLE;
        }
        boolean hasPopupKeys = (key.getPopupKeys() != null);
        keyPreviewView.setPreviewBackground(hasPopupKeys, keyPreviewPosition);
        Colors colors = Settings.getValues().mColors;
        colors.setBackground(keyPreviewView, ColorType.KEY_PREVIEW_BACKGROUND);

        // The key preview is placed vertically above the top edge of the parent key with an
        // arbitrary offset.
        int previewY = key.getY() - previewHeight + key.getHeight() - mParams.mPreviewOffset
                + CoordinateUtils.y(originCoords);

        ViewLayoutUtils.placeViewAt(keyPreviewView, previewX, previewY, previewWidth, previewHeight);
        keyPreviewView.setPivotX(previewWidth / 2.0f);
        keyPreviewView.setPivotY(previewHeight);
    }

    // Keyboard: the style that shows iOS's input callout in place of the white preview; the style is
    // tested as Colors.selectAndColorDrawable does (themeStyle == STYLE_GLASS)
    private static boolean isGlass() {
        return KeyboardTheme.STYLE_GLASS.equals(Settings.getValues().mColors.getThemeStyle());
    }

    // Keyboard: places the Glass callout, ported from InputCallout.position, positionX and positionY
    // (https://github.com/KeyboardKit/KeyboardKit/blob/9.9.1/Sources/KeyboardKit/Callouts/Callouts%2BInputCallout.swift):
    // the view is the callout's bounding box with its bottom on the key's bottom (the body lies over the key,
    // the bubble above it, over the suggestion strip if need be); the bubble is kept inside the keyboard's
    // width as placeKeyPreview does for HeliBoard's preview. The ground is found as GlassKeyDrawable.place
    // does, with View.getLocationInWindow (https://developer.android.com/reference/android/view/View#getLocationInWindow(int[])).
    private void placeGlassCallout(final Key key, final KeyPreviewView view, final GlassCalloutDrawable callout,
            final int fullKeyboardViewWidth, final int[] originCoords) {
        final int originX = CoordinateUtils.x(originCoords);
        final int keyX = key.getDrawX() + originX;
        final int keyY = key.getY() + CoordinateUtils.y(originCoords);
        final boolean landscape = view.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        final float textWidth = view.getPaint().measureText(String.valueOf(view.getText()));
        final RectF box = callout.layout(key.getDrawWidth(), key.getHeight(), textWidth, landscape,
                originX - keyX, originX + fullKeyboardViewWidth - keyX);
        final int x = keyX + (int) box.left;
        final int y = keyY + (int) box.top;
        final int width = (int) box.width();
        final int height = (int) box.height();
        // The label is centred in the bubble.
        final RectF bubble = callout.getBubble();
        view.setPadding(Math.round(bubble.left), Math.round(bubble.top),
                Math.max(0, width - Math.round(bubble.right)), Math.max(0, height - Math.round(bubble.bottom)));
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        // The popup keys panel keeps the offset set by MainKeyboardView.onKeyPressed.
        final int visibleOffset = mParams.getVisibleOffset();
        mParams.setGeometry(view);
        mParams.setVisibleOffset(visibleOffset);
        // The callout lies in the keyboard frame that carries the ground, as the keys do.
        final ViewGroup placerView = (ViewGroup) view.getParent();
        final View frame = placerView.getRootView().findViewById(R.id.main_keyboard_frame);
        if (frame != null) {
            final int[] frameAt = new int[2];
            final int[] placerAt = new int[2];
            frame.getLocationInWindow(frameAt);
            placerView.getLocationInWindow(placerAt);
            callout.setPlace(x + placerAt[0] - frameAt[0], y + placerAt[1] - frameAt[1], frame.getWidth(), frame.getHeight());
        } else {
            callout.setPlace(0f, 0f, 0f, 0f);
        }
        ViewLayoutUtils.placeViewAt(view, x, y, width, height);
        view.setPivotX(width / 2.0f);
        view.setPivotY(height);
    }

    void showKeyPreview(final Key key, final KeyPreviewView keyPreviewView) {
        keyPreviewView.setVisibility(View.VISIBLE);
        mShowingKeyPreviewViews.put(key, keyPreviewView);
    }

}
