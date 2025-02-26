package net.osmand.plus.voice;

import android.content.Context;
import android.media.AudioManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.IndexConstants;
import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.api.AudioFocusHelper;
import net.osmand.plus.api.AudioFocusHelperImpl;
import net.osmand.plus.routing.VoiceRouter;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;

import org.apache.commons.logging.Log;
import org.mozilla.javascript.ScriptableObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;

public abstract class CommandPlayer {

	private static final Log log = PlatformUtil.getLog(CommandPlayer.class);

	// Turn types
	public static final String A_LEFT = "left";
	public static final String A_LEFT_SH = "left_sh";
	public static final String A_LEFT_SL = "left_sl";
	public static final String A_LEFT_KEEP = "left_keep";
	public static final String A_RIGHT = "right";
	public static final String A_RIGHT_SH = "right_sh";
	public static final String A_RIGHT_SL = "right_sl";
	public static final String A_RIGHT_KEEP = "right_keep";

	private static boolean bluetoothScoRunning;
	// Only for debugging
	private static String bluetoothScoStatus = "-";

	protected OsmandApplication app;
	protected OsmandSettings settings;
	protected ApplicationMode applicationMode;

	protected final ScriptableObject jsScope;
	protected final VoiceRouter voiceRouter;

	private AudioFocusHelper mAudioFocusHelper;

	protected final File voiceProviderDir;
	protected final String language;
	protected int streamType;

	@NonNull
	public static CommandPlayer createCommandPlayer(@NonNull OsmandApplication app,
	                                                @NonNull ApplicationMode appMode,
	                                                @NonNull String voiceProvider) throws CommandPlayerException {

		File voicesDir = app.getAppPath(IndexConstants.VOICE_INDEX_DIR);
		File voiceProviderDir = new File(voicesDir, voiceProvider);
		if (!voiceProviderDir.exists()) {
			throw new CommandPlayerException(app.getString(R.string.voice_data_unavailable));
		}

		VoiceRouter voiceRouter = app.getRoutingHelper().getVoiceRouter();
		if (JsTtsCommandPlayer.isMyData(voiceProviderDir)) {
			return new JsTtsCommandPlayer(app, appMode, voiceRouter, voiceProviderDir);
		} else if (JsMediaCommandPlayer.isMyData(voiceProviderDir)) {
			return new JsMediaCommandPlayer(app, appMode, voiceRouter, voiceProviderDir);
		}
		throw new CommandPlayerException(app.getString(R.string.voice_data_not_supported));
	}

	protected CommandPlayer(@NonNull OsmandApplication app,
	                        @NonNull ApplicationMode applicationMode,
	                        @NonNull VoiceRouter voiceRouter,
	                        @NonNull File voiceProviderDir) throws CommandPlayerException {
		this.app = app;
		this.settings = app.getSettings();
		this.applicationMode = applicationMode;
		this.voiceRouter = voiceRouter;
		this.streamType = settings.AUDIO_MANAGER_STREAM.getModeValue(applicationMode);
		this.voiceProviderDir = voiceProviderDir;
		this.language = defineVoiceProviderLanguage();
		this.jsScope = initializeJsScope();
	}

	@NonNull
	private String defineVoiceProviderLanguage() {
		return voiceProviderDir.getName()
				.replace(IndexConstants.VOICE_PROVIDER_SUFFIX, "")
				.replace("-formal", "")
				.replace("-casual", "");
	}

	@NonNull
	private ScriptableObject initializeJsScope() {
		org.mozilla.javascript.Context context = org.mozilla.javascript.Context.enter();
		context.setOptimizationLevel(-1);
		ScriptableObject jsScope = context.initSafeStandardObjects();
		try {
			String ttsFilePath = getTtsFileFromDir(voiceProviderDir).getAbsolutePath();
			BufferedReader bufferedReader = new BufferedReader(new FileReader(ttsFilePath));
			context.evaluateReader(jsScope, bufferedReader, "JS", 1, null);
			bufferedReader.close();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			org.mozilla.javascript.Context.exit();
		}
		return jsScope;
	}

	@NonNull
	public abstract CommandBuilder newCommandBuilder();

	public abstract boolean supportsStructuredStreetNames();

	@NonNull
	public abstract List<String> playCommands(@NonNull CommandBuilder builder);

	public abstract void stop();

	@NonNull
	public abstract File getTtsFileFromDir(@NonNull File voiceProviderDir);

	@NonNull
	public String getLanguage() {
		return language;
	}

	@NonNull
	public String getCurrentVoice() {
		return voiceProviderDir.getName();
	}

	public void clear() {
		abandonAudioFocus();
	}

	public void updateAudioStream(int streamType) {
		this.streamType = streamType;
	}

	protected synchronized void requestAudioFocus() {
		log.debug("requestAudioFocus");
		mAudioFocusHelper = createAudioFocusHelper();
		if (mAudioFocusHelper != null && app != null) {
			boolean audioFocusGranted = mAudioFocusHelper.requestAudFocus(app, applicationMode, streamType);
			if (audioFocusGranted && settings.AUDIO_MANAGER_STREAM.getModeValue(applicationMode) == 0) {
				startBluetoothSco();
			}
		}
	}

	@Nullable
	private AudioFocusHelper createAudioFocusHelper() {
		try {
			return new AudioFocusHelperImpl();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return null;
		}
	}
	
	protected synchronized void abandonAudioFocus() {
		log.debug("abandonAudioFocus");
		if ((app != null && settings.AUDIO_MANAGER_STREAM.getModeValue(applicationMode) == 0)
				|| bluetoothScoRunning) {
			stopBluetoothSco();
		}
		if (app != null && mAudioFocusHelper != null) {
			mAudioFocusHelper.abandonAudFocus(app, applicationMode, streamType);
		}
		mAudioFocusHelper = null;
	}

	// Hardy, 2016-07-03: Establish a low quality BT SCO (Synchronous Connection-Oriented) link to interrupt e.g. a car stereo FM radio
	private synchronized void startBluetoothSco() {
		try {
			AudioManager mAudioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
			if (mAudioManager == null || !mAudioManager.isBluetoothScoAvailableOffCall()) {
				bluetoothScoRunning = false;
				bluetoothScoStatus = "Reported not available.";
				return;
			}

			mAudioManager.setMode(AudioManager.MODE_NORMAL);
			mAudioManager.startBluetoothSco();
			mAudioManager.setBluetoothScoOn(true);
			mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
			bluetoothScoRunning = true;
			bluetoothScoStatus = "Available, initialized OK.";
		} catch (Exception e) {
			log.error("Failed to start Bluetooth SCO", e);
			bluetoothScoRunning = false;
			bluetoothScoStatus = "Available, but not initialized.\n(" + e.getMessage() + ")";
		}
	}

	private synchronized void stopBluetoothSco() {
		AudioManager mAudioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
		if (mAudioManager != null) {
			mAudioManager.setBluetoothScoOn(false);
			mAudioManager.stopBluetoothSco();
			mAudioManager.setMode(AudioManager.MODE_NORMAL);
			bluetoothScoRunning = false;
		}
	}

	public static boolean isBluetoothScoRunning() {
		return bluetoothScoRunning;
	}

	@NonNull
	public static String getBluetoothScoStatus() {
		return bluetoothScoStatus;
	}
}