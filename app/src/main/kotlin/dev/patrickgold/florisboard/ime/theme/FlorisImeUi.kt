/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.theme

import androidx.annotation.StringRes
import dev.patrickgold.florisboard.R

/** Snygg element names are persisted in theme stylesheets and must remain stable. */
@Suppress("ktlint:standard:property-naming")
enum class FlorisImeUi(val elementName: String, @param:StringRes val resId: Int) {
    Root("root", R.string.snygg__rule_element__root),
    Window("window", R.string.snygg__rule_element__window),
    WindowInner("window-inner", R.string.snygg__rule_element__window_inner),
    WindowMoveHandle("window-move-handle", R.string.snygg__rule_element__window_move_handle),
    WindowResizeAction("window-resize-action", R.string.snygg__rule_element__window_resize_action),
    WindowResizeHandle("window-resize-handle", R.string.snygg__rule_element__window_resize_handle),
    WindowResizeOverlayFixed("window-resize-overlay-fixed", R.string.snygg__rule_element__window_resize_overlay_fixed),
    FloatingDockToFixedIndicator(
        "floating-dock-to-fixed-indicator",
        R.string.snygg__rule_element__floating_dock_to_fixed_indicator,
    ),

    Key("key", R.string.snygg__rule_element__key),
    KeyIcon("key-icon", R.string.snygg__rule_element__key_icon),
    KeyHint("key-hint", R.string.snygg__rule_element__key_hint),
    KeyPopupBox("key-popup-box", R.string.snygg__rule_element__key_popup_box),
    KeyPopupElement("key-popup-element", R.string.snygg__rule_element__key_popup_element),
    KeyPopupExtendedIndicator(
        "key-popup-extended-indicator",
        R.string.snygg__rule_element__key_popup_extended_indicator,
    ),

    ClipboardHeader("clipboard-header", R.string.snygg__rule_element__clipboard_header),
    ClipboardHeaderButton("clipboard-header-button", R.string.snygg__rule_element__clipboard_header_button),
    ClipboardHeaderText("clipboard-header-text", R.string.snygg__rule_element__clipboard_header_text),
    ClipboardSubheader("clipboard-subheader", R.string.snygg__rule_element__clipboard_subheader),
    ClipboardContent("clipboard-content", R.string.snygg__rule_element__clipboard_content),
    ClipboardFilterRow("clipboard-filter-row", R.string.snygg__rule_element__clipboard_filter_row),
    ClipboardFilterChip("clipboard-filter-chip", R.string.snygg__rule_element__clipboard_filter_chip),
    ClipboardFilterChipIcon("clipboard-filter-chip-icon", R.string.snygg__rule_element__clipboard_filter_chip_icon),
    ClipboardFilterChipText("clipboard-filter-chip-text", R.string.snygg__rule_element__clipboard_filter_chip_text),
    ClipboardGrid("clipboard-grid", R.string.snygg__rule_element__clipboard_grid),
    ClipboardItem("clipboard-item", R.string.snygg__rule_element__clipboard_item),
    ClipboardItemDescription("clipboard-item-description", R.string.snygg__rule_element__clipboard_item_description),
    ClipboardItemPopup("clipboard-item-popup", R.string.snygg__rule_element__clipboard_item_popup),
    ClipboardItemTimestamp("clipboard-item-timestamp", R.string.snygg__rule_element__clipboard_item_timestamp),
    ClipboardItemActions("clipboard-item-actions", R.string.snygg__rule_element__clipboard_item_actions),
    ClipboardItemAction("clipboard-item-action", R.string.snygg__rule_element__clipboard_item_action),
    ClipboardItemActionIcon("clipboard-item-action-icon", R.string.snygg__rule_element__clipboard_item_action_icon),
    ClipboardItemActionText("clipboard-item-action-text", R.string.snygg__rule_element__clipboard_item_action_text),
    ClipboardClearAllDialog("clipboard-clear-all-dialog", R.string.snygg__rule_element__clipboard_clear_all_dialog),
    ClipboardClearAllDialogMessage(
        "clipboard-clear-all-dialog-message",
        R.string.snygg__rule_element__clipboard_clear_all_dialog_message,
    ),
    ClipboardClearAllDialogButtons(
        "clipboard-clear-all-dialog-buttons",
        R.string.snygg__rule_element__clipboard_clear_all_dialog_buttons,
    ),
    ClipboardClearAllDialogButton(
        "clipboard-clear-all-dialog-button",
        R.string.snygg__rule_element__clipboard_clear_all_dialog_button,
    ),
    ClipboardHistoryDisabledTitle(
        "clipboard-history-disabled-title",
        R.string.snygg__rule_element__clipboard_history_disabled_title,
    ),
    ClipboardHistoryDisabledMessage(
        "clipboard-history-disabled-message",
        R.string.snygg__rule_element__clipboard_history_disabled_message,
    ),
    ClipboardHistoryDisabledButton(
        "clipboard-history-disabled-button",
        R.string.snygg__rule_element__clipboard_history_disabled_button,
    ),
    ClipboardHistoryLockedTitle(
        "clipboard-history-locked-title",
        R.string.snygg__rule_element__clipboard_history_locked_title,
    ),
    ClipboardHistoryLockedMessage(
        "clipboard-history-locked-message",
        R.string.snygg__rule_element__clipboard_history_locked_message,
    ),

    ExtractedLandscapeInputLayout(
        "extracted-landscape-input-layout",
        R.string.snygg__rule_element__extracted_landscape_input_layout,
    ),
    ExtractedLandscapeInputField(
        "extracted-landscape-input-field",
        R.string.snygg__rule_element__extracted_landscape_input_field,
    ),
    ExtractedLandscapeInputAction(
        "extracted-landscape-input-action",
        R.string.snygg__rule_element__extracted_landscape_input_action,
    ),

    GlideTrail("glide-trail", R.string.snygg__rule_element__glide_trail),

    IncognitoModeIndicator("incognito-mode-indicator", R.string.snygg__rule_element__incognito_mode_indicator),

    InlineAutofillChip("inline-autofill-chip", R.string.snygg__rule_element__inline_autofill_chip),

    Media("media", R.string.snygg__rule_element__media),

    MediaEmojiSubheader("media-emoji-subheader", R.string.snygg__rule_element__media_emoji_subheader),
    MediaEmojiKey("media-emoji-key", R.string.snygg__rule_element__media_emoji_key),
    MediaEmojiKeyPopupBox("media-emoji-key-popup-box", R.string.snygg__rule_element__media_emoji_key_popup_box),
    MediaEmojiKeyPopupElement(
        "media-emoji-key-popup-element",
        R.string.snygg__rule_element__media_emoji_key_popup_element,
    ),
    MediaEmojiKeyPopupExtendedIndicator(
        "media-emoji-key-popup-extended-indicator",
        R.string.snygg__rule_element__media_emoji_key_popup_extended_indicator,
    ),
    MediaEmojiTab("media-emoji-tab", R.string.snygg__rule_element__media_emoji_tab),

    MediaBottomRow("media-bottom-row", R.string.snygg__rule_element__media_bottom_row),
    MediaBottomRowButton("media-bottom-row-button", R.string.snygg__rule_element__media_bottom_row_button),

    OneHandedPanel("one-handed-panel", R.string.snygg__rule_element__one_handed_panel),
    OneHandedPanelButton("one-handed-panel-button", R.string.snygg__rule_element__one_handed_panel_button),

    Smartbar("smartbar", R.string.snygg__rule_element__smartbar),
    SmartbarSharedActionsRow("smartbar-shared-actions-row", R.string.snygg__rule_element__smartbar_shared_actions_row),
    SmartbarSharedActionsToggle(
        "smartbar-shared-actions-toggle",
        R.string.snygg__rule_element__smartbar_shared_actions_toggle,
    ),
    SmartbarSharedActionsToggleIcon(
        "smartbar-shared-actions-toggle-icon",
        R.string.snygg__rule_element__smartbar_shared_actions_toggle_icon,
    ),
    SmartbarExtendedActionsRow(
        "smartbar-extended-actions-row",
        R.string.snygg__rule_element__smartbar_extended_actions_row,
    ),
    SmartbarExtendedActionsToggle(
        "smartbar-extended-actions-toggle",
        R.string.snygg__rule_element__smartbar_extended_actions_toggle,
    ),
    SmartbarExtendedActionsToggleIcon(
        "smartbar-extended-actions-toggle-icon",
        R.string.snygg__rule_element__smartbar_extended_actions_toggle_icon,
    ),
    SmartbarActionKey("smartbar-action-key", R.string.snygg__rule_element__smartbar_action_key),
    SmartbarActionKeyIcon("smartbar-action-key-icon", R.string.snygg__rule_element__smartbar_action_key_icon),
    SmartbarActionKeyText("smartbar-action-key-text", R.string.snygg__rule_element__smartbar_action_key_text),

    SmartbarActionTile("smartbar-action-tile", R.string.snygg__rule_element__smartbar_action_tile),
    SmartbarActionTileIcon("smartbar-action-tile-icon", R.string.snygg__rule_element__smartbar_action_tile_icon),
    SmartbarActionTileText("smartbar-action-tile-text", R.string.snygg__rule_element__smartbar_action_tile_text),
    SmartbarActionsOverflow("smartbar-actions-overflow", R.string.snygg__rule_element__smartbar_actions_overflow),
    SmartbarActionsOverflowCustomizeButton(
        "smartbar-actions-overflow-customize-button",
        R.string.snygg__rule_element__smartbar_actions_overflow_customize_button,
    ),

    SmartbarActionsEditor("smartbar-actions-editor", R.string.snygg__rule_element__smartbar_actions_editor),
    SmartbarActionsEditorHeader(
        "smartbar-actions-editor-header",
        R.string.snygg__rule_element__smartbar_actions_editor_header,
    ),
    SmartbarActionsEditorHeaderButton(
        "smartbar-actions-editor-header-button",
        R.string.snygg__rule_element__smartbar_actions_editor_header_button,
    ),
    SmartbarActionsEditorSubheader(
        "smartbar-actions-editor-subheader",
        R.string.snygg__rule_element__smartbar_actions_editor_subheader,
    ),
    SmartbarActionsEditorTileGrid(
        "smartbar-actions-editor-tile-grid",
        R.string.snygg__rule_element__smartbar_actions_editor_tile_grid,
    ),
    SmartbarActionsEditorTile(
        "smartbar-actions-editor-tile",
        R.string.snygg__rule_element__smartbar_actions_editor_tile,
    ),
    SmartbarActionsEditorTileIcon(
        "smartbar-actions-editor-tile-icon",
        R.string.snygg__rule_element__smartbar_actions_editor_tile_icon,
    ),
    SmartbarActionsEditorTileText(
        "smartbar-actions-editor-tile-text",
        R.string.snygg__rule_element__smartbar_actions_editor_tile_text,
    ),

    SmartbarCandidatesRow("smartbar-candidates-row", R.string.snygg__rule_element__smartbar_candidates_row),
    SmartbarCandidateWord("smartbar-candidate-word", R.string.snygg__rule_element__smartbar_candidate_word),
    SmartbarCandidateWordText(
        "smartbar-candidate-word-text",
        R.string.snygg__rule_element__smartbar_candidate_word_text,
    ),
    SmartbarCandidateWordSecondaryText(
        "smartbar-candidate-word-secondary-text",
        R.string.snygg__rule_element__smartbar_candidate_word_secondary_text,
    ),
    SmartbarCandidateClip("smartbar-candidate-clip", R.string.snygg__rule_element__smartbar_candidate_clip),
    SmartbarCandidateClipIcon(
        "smartbar-candidate-clip-icon",
        R.string.snygg__rule_element__smartbar_candidate_clip_icon,
    ),
    SmartbarCandidateClipText(
        "smartbar-candidate-clip-text",
        R.string.snygg__rule_element__smartbar_candidate_clip_text,
    ),
    SmartbarCandidateSpacer("smartbar-candidate-spacer", R.string.snygg__rule_element__smartbar_candidate_spacer),

    SubtypePanel("subtype-panel", R.string.snygg__rule_element__subtype_panel),
    SubtypePanelHeader("subtype-panel-header", R.string.snygg__rule_element__subtype_panel_header),
    SubtypePanelList("subtype-panel-list", R.string.snygg__rule_element__subtype_panel_list),
    SubtypePanelListItem("subtype-panel-list-item", R.string.snygg__rule_element__subtype_panel_list_item),
    SubtypePanelListItemIconLeading(
        "subtype-panel-list-item-icon-leading",
        R.string.snygg__rule_element__subtype_panel_list_item_icon_leading,
    ),
    SubtypePanelListItemText(
        "subtype-panel-list-item-text",
        R.string.snygg__rule_element__subtype_panel_list_item_text,
    ),
    ;

    companion object {
        val elementNames by lazy { entries.map { it.elementName } }

        val elementNamesToOrdinals by lazy { entries.associate { it.elementName to it.ordinal } }

        val elementNamesToTranslation by lazy {
            buildMap {
                put("defines", R.string.snygg__rule_annotation__defines)
                put("font", R.string.snygg__rule_annotation__font)
                FlorisImeUi.entries.associateTo(this) { it.elementName to it.resId }
            }
        }
    }

    object Attr {
        const val Code = "code"
        const val Mode = "mode"
        const val ShiftState = "shiftstate"
        const val WindowMode = "windowmode"
    }
}
