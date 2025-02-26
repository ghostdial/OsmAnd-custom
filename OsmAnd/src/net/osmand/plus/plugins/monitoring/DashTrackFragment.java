package net.osmand.plus.plugins.monitoring;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.GPXUtilities.GPXFile;
import net.osmand.GPXUtilities.WptPt;
import net.osmand.IndexConstants;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.dashboard.DashBaseFragment;
import net.osmand.plus.dashboard.DashboardOnMap;
import net.osmand.plus.dashboard.tools.DashFragmentData;
import net.osmand.plus.helpers.GpxUiHelper;
import net.osmand.plus.helpers.GpxUiHelper.GPXInfo;
import net.osmand.plus.myplaces.ui.AvailableGPXFragment;
import net.osmand.plus.myplaces.ui.FavoritesActivity;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.settings.backend.OsmAndAppCustomization;
import net.osmand.plus.track.GpxSelectionParams;
import net.osmand.plus.track.fragments.TrackMenuFragment;
import net.osmand.plus.track.helpers.GpxFileLoaderTask;
import net.osmand.plus.track.helpers.GpxSelectionHelper;
import net.osmand.plus.track.helpers.SavingTrackHelper;
import net.osmand.plus.track.helpers.SelectedGpxFile;
import net.osmand.plus.utils.OsmAndFormatter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Denis
 * on 21.01.2015.
 */
public class DashTrackFragment extends DashBaseFragment {

	public static final String TAG = "DASH_TRACK_FRAGMENT";
	public static final int TITLE_ID = R.string.shared_string_tracks;

	private static final String ROW_NUMBER_TAG = TAG + "_row_number";

	private static final DashFragmentData.ShouldShowFunction SHOULD_SHOW_FUNCTION =
			new DashboardOnMap.DefaultShouldShow() {
				@Override
				public int getTitleId() {
					return TITLE_ID;
				}
			};
	static final DashFragmentData FRAGMENT_DATA =
			new DashFragmentData(TAG, DashTrackFragment.class, SHOULD_SHOW_FUNCTION, 110, ROW_NUMBER_TAG);

	private boolean updateEnable;

	@Override
	public View initView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = getActivity().getLayoutInflater().inflate(R.layout.dash_common_fragment, container, false);
		TextView header = view.findViewById(R.id.fav_text);
		header.setText(TITLE_ID);

		(view.findViewById(R.id.show_all)).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				Activity activity = getActivity();
				OsmAndAppCustomization appCustomization = getMyApplication().getAppCustomization();
				Intent favorites = new Intent(activity, appCustomization.getFavoritesActivity());
				getMyApplication().getSettings().FAVORITES_TAB.set(FavoritesActivity.GPX_TAB);
				favorites.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
				activity.startActivity(favorites);
				closeDashboard();
			}
		});
		return view;
	}

	@Override
	public void onOpenDash() {
		updateEnable = true;
		setupGpxFiles();
	}


	@Override
	public void onCloseDash() {
		updateEnable = false;
	}

	private void setupGpxFiles() {
		View mainView = getView();
		File dir = getMyApplication().getAppPath(IndexConstants.GPX_INDEX_DIR);
		OsmandApplication app = getMyApplication();
		if (app == null) {
			return;
		}

		List<String> list = new ArrayList<String>();
		for (SelectedGpxFile sg : app.getSelectedGpxHelper().getSelectedGPXFiles()) {
			if (!sg.isShowCurrentTrack()) {
				GPXFile gpxFile = sg.getGpxFile();
				if (gpxFile != null) {
					list.add(gpxFile.path);
				}
			}
		}
		// 10 is the maximum length of the list. The actual length is determined later by
		// DashboardOnMap.handleNumberOfRows()
		int totalCount = 10;
		if (app.getSettings().SAVE_GLOBAL_TRACK_TO_GPX.get()) {
			totalCount--;
		}
		if (list.size() < totalCount) {
			List<GPXInfo> res = GpxUiHelper.getSortedGPXFilesInfoByDate(dir, true);
			for (GPXInfo r : res) {
				String name = r.getFileName();
				if (!list.contains(name)) {
					list.add(name);
					if (list.size() >= totalCount) {
						break;
					}
				}
			}
		}

		if (list.size() == 0 && !OsmandPlugin.isActive(OsmandMonitoringPlugin.class)) {
			(mainView.findViewById(R.id.main_fav)).setVisibility(View.GONE);
			return;
		} else {
			(mainView.findViewById(R.id.main_fav)).setVisibility(View.VISIBLE);
			DashboardOnMap.handleNumberOfRows(list,
					getMyApplication().getSettings(), ROW_NUMBER_TAG);
		}

		LinearLayout tracks = mainView.findViewById(R.id.items);
		tracks.removeAllViews();

		LayoutInflater inflater = getActivity().getLayoutInflater();
		if (OsmandPlugin.isActive(OsmandMonitoringPlugin.class)) {
			View view = inflater.inflate(R.layout.dash_gpx_track_item, null, false);

			createCurrentTrackView(view);
			((TextView) view.findViewById(R.id.name)).setText(R.string.shared_string_currently_recording_track);
			updateCurrentTrack(view, getActivity(), app);
			view.setOnClickListener(v -> openGpxContextMenu(null));
			view.findViewById(R.id.divider_dash).setVisibility(View.VISIBLE);
			tracks.addView(view);
			startHandler(view);
		}

		for (String filename : list) {
			File file = new File(filename);
			AvailableGPXFragment.GpxInfo info = new AvailableGPXFragment.GpxInfo();
			info.subfolder = "";
			info.file = file;
			View itemView = inflater.inflate(R.layout.dash_gpx_track_item, null, false);
			AvailableGPXFragment.updateGpxInfoView(itemView, info, app, true, null);

			itemView.setOnClickListener(v -> openGpxContextMenu(file));
			ImageButton showOnMap = itemView.findViewById(R.id.show_on_map);
			showOnMap.setVisibility(View.VISIBLE);
			showOnMap.setContentDescription(getString(R.string.shared_string_show_on_map));
			updateShowOnMap(app, file, itemView, showOnMap);
			tracks.addView(itemView);
		}
	}

	public static void createCurrentTrackView(View v) {
		((TextView) v.findViewById(R.id.name)).setText(R.string.shared_string_currently_recording_track);
		v.findViewById(R.id.icon).setVisibility(View.GONE);
		v.findViewById(R.id.time_icon).setVisibility(View.GONE);
		v.findViewById(R.id.divider_dash).setVisibility(View.GONE);
		v.findViewById(R.id.divider_list).setVisibility(View.GONE);
		v.findViewById(R.id.options).setVisibility(View.GONE);
		v.findViewById(R.id.stop).setVisibility(View.VISIBLE);
		v.findViewById(R.id.check_item).setVisibility(View.GONE);
	}

	public static void updateCurrentTrack(View v, Activity ctx, OsmandApplication app) {
		OsmandMonitoringPlugin plugin = OsmandPlugin.getActivePlugin(OsmandMonitoringPlugin.class);
		if (v == null || ctx == null || app == null || plugin == null) {
			return;
		}
		boolean isRecording = app.getSettings().SAVE_GLOBAL_TRACK_TO_GPX.get();
		ImageButton stop = v.findViewById(R.id.stop);
		if (isRecording) {
			stop.setImageDrawable(app.getUIUtilities().getThemedIcon(R.drawable.ic_action_rec_stop));
			stop.setContentDescription(app.getString(R.string.gpx_monitoring_stop));
		} else {
			stop.setImageDrawable(app.getUIUtilities().getThemedIcon(R.drawable.ic_action_rec_start));
			stop.setContentDescription(app.getString(R.string.gpx_monitoring_start));
		}
		stop.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (isRecording) {
					plugin.stopRecording();
				} else if (app.getLocationProvider().checkGPSEnabled(ctx)) {
					plugin.startGPXMonitoring(ctx);
				}
			}
		});
		SavingTrackHelper sth = app.getSavingTrackHelper();
		ImageButton save = v.findViewById(R.id.show_on_map);
		save.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				plugin.saveCurrentTrack();
			}
		});
		if (sth.getPoints() > 0 || sth.getDistance() > 0) {
			save.setVisibility(View.VISIBLE);
		} else {
			save.setVisibility(View.GONE);
		}
		save.setImageDrawable(app.getUIUtilities().getThemedIcon(R.drawable.ic_action_gsave_dark));
		save.setContentDescription(app.getString(R.string.save_current_track));

		((TextView) v.findViewById(R.id.points_count)).setText(String.valueOf(sth.getPoints()));
		((TextView) v.findViewById(R.id.distance))
				.setText(OsmAndFormatter.getFormattedDistance(sth.getDistance(), app));
		v.findViewById(R.id.points_icon).setVisibility(View.VISIBLE);
		ImageView distance = v.findViewById(R.id.distance_icon);
		distance.setVisibility(View.VISIBLE);
		distance.setImageDrawable(app.getUIUtilities().getThemedIcon(R.drawable.ic_action_distance_16));
		ImageView pointsCount = v.findViewById(R.id.points_icon);
		pointsCount.setVisibility(View.VISIBLE);
		pointsCount.setImageDrawable(app.getUIUtilities().getThemedIcon(R.drawable.ic_action_waypoint_16));
	}

	private void openGpxContextMenu(@Nullable File gpxFile) {
		Activity activity = getActivity();
		if (activity != null) {
			TrackMenuFragment.openTrack(activity, gpxFile, null);
			closeDashboard();
		}
	}

	private void updateShowOnMap(OsmandApplication app, File f, View pView, ImageButton showOnMap) {
		GpxSelectionHelper selectedGpxHelper = app.getSelectedGpxHelper();
		SelectedGpxFile selected = selectedGpxHelper.getSelectedFileByPath(f.getAbsolutePath());
		if (selected != null) {
			showOnMap.setImageDrawable(app.getUIUtilities().getIcon(R.drawable.ic_show_on_map, R.color.color_distance));
			showOnMap.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					GpxSelectionParams params = GpxSelectionParams.newInstance()
							.hideFromMap().syncGroup().saveSelection();
					selectedGpxHelper.selectGpxFile(selected.getGpxFile(), params);
					AvailableGPXFragment.GpxInfo info = new AvailableGPXFragment.GpxInfo();
					info.subfolder = "";
					info.file = f;
					AvailableGPXFragment.updateGpxInfoView(pView, info, app, true, null);
					updateShowOnMap(app, f, v, showOnMap);
				}
			});
		} else {
			showOnMap.setImageDrawable(app.getUIUtilities().getThemedIcon(R.drawable.ic_show_on_map));
			showOnMap.setOnClickListener(v -> GpxFileLoaderTask.loadGpxFile(f, getActivity(), gpxFile -> {
				Activity activity = getActivity();
				if (activity != null) {
					showOnMap(activity, gpxFile);
				}
				return true;
			}));
		}
	}

	private void showOnMap(@NonNull Activity activity, @NonNull GPXFile gpxFile) {
		if (gpxFile.isEmpty()) {
			app.showToastMessage(R.string.gpx_file_is_empty);
			return;
		}

		WptPt point = gpxFile.getLastPoint();
		if (point == null) {
			point = gpxFile.findPointToShow();
		}
		if (point != null) {
			settings.setMapLocationToShow(point.lat, point.lon, settings.getLastKnownMapZoom());
		}
		app.getSelectedGpxHelper().setGpxFileToDisplay(gpxFile);
		MapActivity.launchMapActivityMoveToTop(activity);
	}

	private void startHandler(View v) {
		Handler updateCurrentRecordingTrack = new Handler();
		updateCurrentRecordingTrack.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (updateEnable) {
					updateCurrentTrack(v, getActivity(), getMyApplication());
					startHandler(v);
				}
			}
		}, 1500);
	}
}
