package net.osmand.plus.views.mapwidgets;

import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;
import net.osmand.plus.views.mapwidgets.widgets.TextInfoWidget;
import net.osmand.plus.views.mapwidgets.widgetstates.WidgetState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import static net.osmand.plus.views.mapwidgets.MapWidgetRegistry.COLLAPSED_PREFIX;
import static net.osmand.plus.views.mapwidgets.MapWidgetRegistry.HIDE_PREFIX;
import static net.osmand.plus.views.mapwidgets.MapWidgetRegistry.SETTINGS_SEPARATOR;

public class SideWidgetInfo extends MapWidgetInfo {

	public SideWidgetInfo(@NonNull String key,
	                      @NonNull TextInfoWidget widget,
	                      @Nullable WidgetState widgetState,
	                      @DrawableRes int daySettingsIconId,
	                      @DrawableRes int nightSettingsIconId,
	                      @StringRes int messageId,
	                      @Nullable String message,
	                      int page,
	                      int order,
	                      @NonNull WidgetsPanel widgetPanel) {
		super(key, widget, widgetState, daySettingsIconId, nightSettingsIconId, messageId, message,
				page, order, widgetPanel);

		if (message != null) {
			widget.setContentTitle(message);
		} else if (messageId != MapWidgetInfo.INVALID_ID) {
			widget.setContentTitle(messageId);
		} else if (widgetState != null) {
			widget.setContentTitle(widgetState.getMenuTitleId());
		}
	}

	@Override
	public boolean isEnabledForAppMode(@NonNull ApplicationMode appMode) {
		List<String> widgetsVisibility = getWidgetsVisibility(appMode);
		if (widgetsVisibility.contains(key) || widgetsVisibility.contains(COLLAPSED_PREFIX + key)) {
			return true;
		} else if (widgetsVisibility.contains(HIDE_PREFIX + key)) {
			return false;
		}
		return appMode.isWidgetVisibleByDefault(key);
	}

	@Override
	public void enableDisableForMode(@NonNull ApplicationMode appMode, boolean enabled) {
		List<String> widgetsVisibility = getWidgetsVisibility(appMode);
		widgetsVisibility.remove(key);
		widgetsVisibility.remove(COLLAPSED_PREFIX + key);
		widgetsVisibility.remove(HIDE_PREFIX + key);
		widgetsVisibility.add(enabled ? key : HIDE_PREFIX + key);

		StringBuilder newVisibilityString = new StringBuilder();
		for (String visibility : widgetsVisibility) {
			newVisibilityString.append(visibility).append(SETTINGS_SEPARATOR);
		}

		getVisibilityPreference().setModeValue(appMode, newVisibilityString.toString());
	}

	@NonNull
	private List<String> getWidgetsVisibility(@NonNull ApplicationMode appMode) {
		String widgetsVisibilityString = getVisibilityPreference().getModeValue(appMode);
		return new ArrayList<>(Arrays.asList(widgetsVisibilityString.split(SETTINGS_SEPARATOR)));
	}

	@NonNull
	private OsmandPreference<String> getVisibilityPreference() {
		return widget.getMyApplication().getSettings().MAP_INFO_CONTROLS;
	}
}