/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.content.Context;
import android.graphics.Paint;

import androidx.annotation.NonNull;

import helium314.keyboard.keyboard.internal.KeyboardBuilder;
import helium314.keyboard.keyboard.internal.KeyboardParams;
import helium314.keyboard.keyboard.internal.PopupKeySpec;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.StringUtils;
import helium314.keyboard.latin.utils.TypefaceUtils;

public final class PopupKeysKeyboard extends Keyboard {
    private final int mDefaultKeyCoordX;
    // Keyboard: the long press panel as iOS's action callout (Glass style), see Builder; the key that was pressed,
    // which side of the keyboard it is on, and the height of the body under the items (0 on a tablet)
    public final boolean mActionCallout;
    public final boolean mActionCalloutLeading;
    public final Key mParentKey;
    public final int mBodyZone;

    PopupKeysKeyboard(final PopupKeysKeyboardParams params) {
        super(params);
        mDefaultKeyCoordX = params.getDefaultKeyCoordX() + params.mAbsolutePopupKeyWidth / 2;
        mActionCallout = params.mActionCallout;
        mActionCalloutLeading = params.mActionCalloutAlign > 0;
        mParentKey = params.mParentKey;
        mBodyZone = params.mBottomPadding;
    }

    public int getDefaultCoordX() {
        return mDefaultKeyCoordX;
    }

    static class PopupKeysKeyboardParams extends KeyboardParams {
        // Keyboard: action callout: 0 none, 1 the items go right from the key, -1 they go left
        public boolean mActionCallout;
        public int mActionCalloutAlign;
        public Key mParentKey;
        public boolean mIsPopupKeysFixedOrder;
        /* package */int mTopRowAdjustment;
        public int mNumRows;
        public int mNumColumns;
        public int mTopKeys;
        public int mLeftKeys;
        public int mRightKeys; // includes default key.
        public int mDividerWidth;
        public int mColumnWidth;

        public PopupKeysKeyboardParams() {
            super();
        }

        /**
         * Set keyboard parameters of popup keys keyboard.
         *
         * @param numKeys number of keys in this popup keys keyboard.
         * @param numColumn number of columns of this popup keys keyboard.
         * @param keyWidth popup keys keyboard key width in pixel, including horizontal gap.
         * @param rowHeight popup keys keyboard row height in pixel, including vertical gap.
         * @param coordXInParent coordinate x of the key preview in parent keyboard.
         * @param parentKeyboardWidth parent keyboard width in pixel.
         * @param isPopupKeysFixedColumn true if popup keys keyboard should have
         *   <code>numColumn</code> columns. Otherwise popup keys keyboard should have
         *   <code>numColumn</code> columns at most.
         * @param isPopupKeysFixedOrder true if the order of popup keys is determined by the order in
         *   the popup keys' specification. Otherwise the order of popup keys is automatically
         *   determined.
         * @param dividerWidth width of divider, zero for no dividers.
         */
        public void setParameters(final int numKeys, final int numColumn, final int keyWidth,
                final int rowHeight, final int coordXInParent, final int parentKeyboardWidth,
                final boolean isPopupKeysFixedColumn, final boolean isPopupKeysFixedOrder,
                final int dividerWidth) {
            mIsPopupKeysFixedOrder = isPopupKeysFixedOrder;
            if (parentKeyboardWidth / keyWidth < Math.min(numKeys, numColumn)) {
                throw new IllegalArgumentException("Keyboard is too small to hold popup keys: "
                        + parentKeyboardWidth + " " + keyWidth + " " + numKeys + " " + numColumn);
            }
            mDefaultAbsoluteKeyWidth = keyWidth;
            mDefaultAbsoluteRowHeight = rowHeight;

            mNumRows = (numKeys + numColumn - 1) / numColumn;
            final int numColumns = isPopupKeysFixedColumn ? Math.min(numKeys, numColumn)
                    : getOptimizedColumns(numKeys, numColumn);
            mNumColumns = numColumns;
            final int topKeys = numKeys % numColumns;
            mTopKeys = topKeys == 0 ? numColumns : topKeys;

            final int numLeftKeys = (numColumns - 1) / 2;
            final int numRightKeys = numColumns - numLeftKeys; // including default key.
            // Maximum number of keys we can layout both side of the parent key
            final int maxLeftKeys = coordXInParent / keyWidth;
            final int maxRightKeys = (parentKeyboardWidth - coordXInParent) / keyWidth;
            int leftKeys, rightKeys;
            if (numLeftKeys > maxLeftKeys) {
                leftKeys = maxLeftKeys;
                rightKeys = numColumns - leftKeys;
            } else if (numRightKeys > maxRightKeys + 1) {
                rightKeys = maxRightKeys + 1; // include default key
                leftKeys = numColumns - rightKeys;
            } else {
                leftKeys = numLeftKeys;
                rightKeys = numRightKeys;
            }
            // If the left keys fill the left side of the parent key, entire popup keys keyboard
            // should be shifted to the right unless the parent key is on the left edge.
            if (maxLeftKeys == leftKeys && leftKeys > 0) {
                leftKeys--;
                rightKeys++;
            }
            // If the right keys fill the right side of the parent key, entire popup keys
            // should be shifted to the left unless the parent key is on the right edge.
            if (maxRightKeys == rightKeys - 1 && rightKeys > 1) {
                leftKeys++;
                rightKeys--;
            }
            if (mActionCalloutAlign != 0) {
                // Keyboard: ActionCallout.positionX: the items start at the key (leading) or end at it (trailing),
                // shifted by whole columns only to stay inside the keyboard
                int preferred = mActionCalloutAlign > 0 ? 0 : numColumns - 1;
                final int lowest = numColumns - 1 - maxRightKeys;
                if (lowest <= maxLeftKeys) preferred = Math.min(Math.max(preferred, lowest), maxLeftKeys);
                leftKeys = Math.max(0, Math.min(numColumns - 1, preferred));
                rightKeys = numColumns - leftKeys;
            }
            mLeftKeys = leftKeys;
            mRightKeys = rightKeys;

            // Adjustment of the top row.
            mTopRowAdjustment = isPopupKeysFixedOrder ? getFixedOrderTopRowAdjustment()
                    : getAutoOrderTopRowAdjustment();
            mDividerWidth = dividerWidth;
            mColumnWidth = mDefaultAbsoluteKeyWidth + mDividerWidth;
            mBaseWidth = mOccupiedWidth = mNumColumns * mColumnWidth - mDividerWidth;
            if (mActionCallout) mBaseWidth = mOccupiedWidth += mLeftPadding + mRightPadding; // Keyboard: the bubble's side padding
            // Need to subtract the bottom row's gutter only.
            mBaseHeight = mOccupiedHeight = mNumRows * mDefaultAbsoluteRowHeight - mVerticalGap
                    + mTopPadding + mBottomPadding;
        }

        private int getFixedOrderTopRowAdjustment() {
            if (mNumRows == 1 || mTopKeys % 2 == 1 || mTopKeys == mNumColumns
                    || mLeftKeys == 0  || mRightKeys == 1) {
                return 0;
            }
            return -1;
        }

        private int getAutoOrderTopRowAdjustment() {
            if (mNumRows == 1 || mTopKeys == 1 || mNumColumns % 2 == mTopKeys % 2
                    || mLeftKeys == 0 || mRightKeys == 1) {
                return 0;
            }
            return -1;
        }

        // Return key position according to column count (0 is default).
        /* package */int getColumnPos(final int n) {
            return mIsPopupKeysFixedOrder ? getFixedOrderColumnPos(n) : getAutomaticColumnPos(n);
        }

        private int getFixedOrderColumnPos(final int n) {
            final int col = n % mNumColumns;
            final int row = n / mNumColumns;
            if (!isTopRow(row)) {
                return col - mLeftKeys;
            }
            final int rightSideKeys = mTopKeys / 2;
            final int leftSideKeys = mTopKeys - (rightSideKeys + 1);
            final int pos = col - leftSideKeys;
            final int numLeftKeys = mLeftKeys + mTopRowAdjustment;
            final int numRightKeys = mRightKeys - 1;
            if (numRightKeys >= rightSideKeys && numLeftKeys >= leftSideKeys) {
                return pos;
            } else if (numRightKeys < rightSideKeys) {
                return pos - (rightSideKeys - numRightKeys);
            } else { // numLeftKeys < leftSideKeys
                return pos + (leftSideKeys - numLeftKeys);
            }
        }

        private int getAutomaticColumnPos(final int n) {
            final int col = n % mNumColumns;
            final int row = n / mNumColumns;
            int leftKeys = mLeftKeys;
            if (isTopRow(row)) {
                leftKeys += mTopRowAdjustment;
            }
            if (col == 0) {
                // default position.
                return 0;
            }

            int pos = 0;
            int right = 1; // include default position key.
            int left = 0;
            int i = 0;
            while (true) {
                // Assign right key if available.
                if (right < mRightKeys) {
                    pos = right;
                    right++;
                    i++;
                }
                if (i >= col)
                    break;
                // Assign left key if available.
                if (left < leftKeys) {
                    left++;
                    pos = -left;
                    i++;
                }
                if (i >= col)
                    break;
            }
            return pos;
        }

        private static int getTopRowEmptySlots(final int numKeys, final int numColumns) {
            final int remainings = numKeys % numColumns;
            return remainings == 0 ? 0 : numColumns - remainings;
        }

        private int getOptimizedColumns(final int numKeys, final int maxColumns) {
            int numColumns = Math.min(numKeys, maxColumns);
            while (getTopRowEmptySlots(numKeys, numColumns) >= mNumRows) {
                numColumns--;
            }
            return numColumns;
        }

        public int getDefaultKeyCoordX() {
            return mLeftKeys * mColumnWidth + mLeftPadding;
        }

        public int getX(final int n, final int row) {
            final int x = getColumnPos(n) * mColumnWidth + getDefaultKeyCoordX();
            if (isTopRow(row)) {
                return x + mTopRowAdjustment * (mColumnWidth / 2);
            }
            return x;
        }

        public int getY(final int row) {
            return (mNumRows - 1 - row) * mDefaultAbsoluteRowHeight + mTopPadding;
        }

        public void markAsEdgeKey(final Key key, final int row) {
            if (row == 0)
                key.markAsTopEdge(this);
            if (isTopRow(row))
                key.markAsBottomEdge(this);
        }

        private boolean isTopRow(final int rowCount) {
            return mNumRows > 1 && rowCount == mNumRows - 1;
        }
    }

    public static class Builder extends KeyboardBuilder<PopupKeysKeyboardParams> {
        private final Key mParentKey;

        private static final float LABEL_PADDING_RATIO = 0.2f;
        private static final float DIVIDER_RATIO = 0.2f;

        /**
         * The builder of PopupKeysKeyboard.
         * @param context the context of {@link PopupKeysKeyboardView}.
         * @param key the {@link Key} that invokes popup keys keyboard.
         * @param keyboard the {@link Keyboard} that contains the parentKey.
         * @param isSinglePopupKeyWithPreview true if the <code>key</code> has just a single
         *        "popup key" and its key popup preview is enabled.
         * @param keyPreviewVisibleWidth the width of visible part of key popup preview.
         * @param keyPreviewVisibleHeight the height of visible part of key popup preview
         * @param paintToMeasure the {@link Paint} object to measure a "popup key" width
         */
        public Builder(final Context context, final Key key, final Keyboard keyboard,
                final boolean isSinglePopupKeyWithPreview, final int keyPreviewVisibleWidth,
                final int keyPreviewVisibleHeight, final Paint paintToMeasure) {
            this(context, key, keyboard, isSinglePopupKeyWithPreview, keyPreviewVisibleWidth,
                    keyPreviewVisibleHeight, paintToMeasure, false);
        }

        /**
         * Keyboard: with [actionCallout] (Glass style) the items are laid out as iOS's ActionCallout
         * (KeyboardKit 9.9.1, Callouts+ActionCallout.swift, itemSize, itemSizeWidthScale, positionX,
         * https://github.com/KeyboardKit/KeyboardKit/blob/9.9.1/Sources/KeyboardKit/Callouts/Callouts%2BActionCallout.swift):
         * an item is the key's size capped at 50 x 50 dp, 1.4 times as wide when alone, with 6 dp above and below,
         * side by side without gaps nor dividers, starting at the key when it is on the leading side of the keyboard
         * and ending at it otherwise; the bubble has the curve width (8 dp) at each side, and under it the body
         * (the key's height) is left free, so the panel reaches down to the key.
         */
        public Builder(final Context context, final Key key, final Keyboard keyboard,
                final boolean isSinglePopupKeyWithPreview, final int keyPreviewVisibleWidth,
                final int keyPreviewVisibleHeight, final Paint paintToMeasure, final boolean actionCallout) {
            super(context, new PopupKeysKeyboardParams());
            mParams.mId = keyboard.mId;
            readAttributes(keyboard.mPopupKeysTemplate);

            // TODO: Popup keys keyboard's vertical gap is currently calculated heuristically.
            // Should revise the algorithm.
            mParams.mVerticalGap = keyboard.mVerticalGap / 2;
            // This {@link PopupKeysKeyboard} is invoked from the <code>key</code>.
            mParentKey = key;
            int calloutItemWidth = 0;
            if (actionCallout) {
                final float density = context.getResources().getDisplayMetrics().density;
                mParams.mActionCallout = true;
                mParams.mParentKey = key;
                mParams.mVerticalGap = 0;
                mParams.mHorizontalGap = 0;
                mParams.mLeftPadding = mParams.mRightPadding = Math.round(8f * density); // curveSize.width
                mParams.mTopPadding = 0;
                // the body of the callout is as high as the key; none on a tablet (ButtonArea.opacity(0))
                mParams.mBottomPadding = context.getResources().getConfiguration().smallestScreenWidthDp >= 600
                        ? 0 : key.getHeight();
                final float widthScale = key.getPopupKeys().length == 1 ? 1.4f : 1f;
                calloutItemWidth = Math.max(1, Math.round(Math.min(key.getDrawWidth(), 50f * density) * widthScale));
                mParams.mActionCalloutAlign = key.getX() + key.getWidth() / 2 < keyboard.mId.getWidth() / 2 ? 1 : -1;
            }

            final int keyWidth, rowHeight;
            if (actionCallout) {
                final float density = context.getResources().getDisplayMetrics().density;
                mParams.mAbsolutePopupKeyWidth = calloutItemWidth;
                final float padding = context.getResources().getDimension(
                        R.dimen.config_popup_keys_keyboard_key_horizontal_padding)
                        + (key.hasLabelsInPopupKeys() ? calloutItemWidth * LABEL_PADDING_RATIO : 0.0f);
                keyWidth = getMaxKeyWidth(key, calloutItemWidth, padding, paintToMeasure);
                mParams.mAbsolutePopupKeyWidth = keyWidth;
                rowHeight = Math.round(Math.min(key.getHeight(), 50f * density) + 2 * 6f * density);
            } else if (isSinglePopupKeyWithPreview) {
                // Use pre-computed width and height if this popup keys keyboard has only one key to
                // mitigate visual flicker between key preview and popup keys keyboard.
                // Caveats for the visual assets: To achieve this effect, both the key preview
                // backgrounds and the popup keys keyboard panel background have the exact same
                // left/right/top paddings. The bottom paddings of both backgrounds don't need to
                // be considered because the vertical positions of both backgrounds were already
                // adjusted with their bottom paddings deducted.
                keyWidth = keyPreviewVisibleWidth;
                rowHeight = keyPreviewVisibleHeight + mParams.mVerticalGap;
            } else {
                final float padding = context.getResources().getDimension(
                        R.dimen.config_popup_keys_keyboard_key_horizontal_padding)
                        + (key.hasLabelsInPopupKeys()
                                ? mParams.mAbsolutePopupKeyWidth * LABEL_PADDING_RATIO : 0.0f);
                keyWidth = getMaxKeyWidth(key, mParams.mAbsolutePopupKeyWidth, padding, paintToMeasure);
                rowHeight = keyboard.mMostCommonKeyHeight;
            }
            final int dividerWidth;
            if (key.needsDividersInPopupKeys() && !actionCallout) {
                dividerWidth = (int)(keyWidth * DIVIDER_RATIO);
            } else {
                dividerWidth = 0;
            }
            final PopupKeySpec[] popupKeys = key.getPopupKeys();
            final int defaultColumns = key.getPopupKeysColumnNumber();
            final int spaceForKeys = (keyboard.mId.getWidth() - mParams.mLeftPadding - mParams.mRightPadding) / Math.max(1, keyWidth);
            int finalNumColumns = spaceForKeys >= Math.min(popupKeys.length, defaultColumns)
                    ? defaultColumns
                    : (spaceForKeys > 0 ? spaceForKeys : defaultColumns);
            if (finalNumColumns < 1) finalNumColumns = 1; // avoid exception in setParameters
            // Keyboard: the first item starts at the key (leading) or the last ends at it (trailing)
            final int coordXInParent = !actionCallout ? key.getX() + key.getWidth() / 2
                    : mParams.mActionCalloutAlign > 0 ? key.getDrawX() + keyWidth / 2
                    : key.getDrawX() + key.getDrawWidth() - keyWidth / 2;
            mParams.setParameters(popupKeys.length, finalNumColumns, keyWidth,
                    rowHeight, coordXInParent, keyboard.mId.getWidth(),
                    key.isPopupKeysFixedColumn(), key.isPopupKeysFixedOrder(), dividerWidth);
        }

        private static int getMaxKeyWidth(final Key parentKey, final int minKeyWidth,
                final float padding, final Paint paint) {
            int maxWidth = minKeyWidth;
            for (final PopupKeySpec spec : parentKey.getPopupKeys()) {
                final String label = spec.mLabel;
                // If the label is single letter, minKeyWidth is enough to hold the label.
                if (label != null && StringUtils.codePointCount(label) > 1) {
                    maxWidth = Math.max(maxWidth,
                            (int)(TypefaceUtils.getStringWidth(label, paint) + padding));
                }
            }
            return maxWidth;
        }

        @Override
        @NonNull
        public PopupKeysKeyboard build() {
            final PopupKeysKeyboardParams params = mParams;
            final int popupKeyFlags = mParentKey.getPopupKeyLabelFlags();
            final PopupKeySpec[] popupKeys = mParentKey.getPopupKeys();
            final int background = mParentKey.hasActionKeyPopups() ? Key.BACKGROUND_TYPE_ACTION : Key.BACKGROUND_TYPE_NORMAL;
            for (int n = 0; n < popupKeys.length; n++) {
                final PopupKeySpec popupKeySpec = popupKeys[n];
                final int row = n / params.mNumColumns;
                final int x = params.getX(n, row);
                final int y = params.getY(row);
                final Key key = popupKeySpec.buildKey(x, y, popupKeyFlags, background, params);
                params.markAsEdgeKey(key, row);
                params.onAddKey(key);

                final int pos = params.getColumnPos(n);
                // The "pos" value represents the offset from the default position. Negative means
                // left of the default position.
                if (params.mDividerWidth > 0 && pos != 0) {
                    final int dividerX = (pos > 0) ? x - params.mDividerWidth
                            : x + params.mAbsolutePopupKeyWidth;
                    final Key divider = new PopupKeyDivider(
                            params, dividerX, y, params.mDividerWidth, params.mDefaultAbsoluteRowHeight);
                    params.onAddKey(divider);
                }
            }
            return new PopupKeysKeyboard(params);
        }
    }

    // Used as a divider maker. A divider is drawn by {@link PopupKeysKeyboardView}.
    public static class PopupKeyDivider extends Key.Spacer {
        public PopupKeyDivider(final KeyboardParams params, final int x, final int y,
                final int width, final int height) {
            super(params, x, y, width, height);
        }
    }
}
