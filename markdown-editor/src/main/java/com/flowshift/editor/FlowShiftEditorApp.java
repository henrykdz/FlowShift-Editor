package com.flowshift.editor;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

import avp.kernel.KassandraKernel;
import avp.kernel.KernelConnector;
import avp.ui.chat.ChatController;
import avp.ui.chat.ChatSatellite;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import utils.localize.LangMap;
import utils.logging.Log;
import utils.resources.OptionsHandler;
import utils.resources.OptionsHandler.OptionKey;
import utils.toast.ToastNotifier;
import utils.tooltip.CustomPopupControl;
import utils.ui.WindowUtils;

/**
 * FlowShift Press - Application Lifecycle Manager. Orchestrates UI loading,
 * controller handshake, and window geometry persistence.
 */
public class FlowShiftEditorApp extends Application {

	private static final String FXML_PATH = "/com/flowshift/editor/MarkdownEditor.fxml";
	private static final String CSS_MAIN = "/com/flowshift/editor/editor-style.css";
	private static final String CSS_SYNTAX = "/com/flowshift/editor/syntax-highlighting.css";
	private static final int MIN_WINDOW_SIZE = 100;
	private static final int DEFAULT_MIN_WIDTH = 800;
	private static final int DEFAULT_MIN_HEIGHT = 600;
	private static final int SHUTDOWN_TIMEOUT_FORCE_EXIT = 3000;
	private static final int SHUTDOWN_TIMEOUT_ACK = 10000;

	private EditorSocketClient bridgeClient;
	private EditorController controllerEditor;
	private Stage mainStage;
	private OptionsHandler settings;

	/**
	 * Default constructor required by JavaFX.
	 */
	public FlowShiftEditorApp() {
		// Constructor intentionally empty - JavaFX restrictions
	}

	@Override
	public void start(Stage stage) {
		try {
			setupGlobalUncaughtExceptionHandler();
			initializeSettings();
			initialize(stage);
			connectBridge();
			setupShutdownHook(stage);
		} catch (Exception e) {
			Log.error(e, "Fatal error during application startup");
			showFatalDialogAndExit(e);
		}
	}

	@Override
	public void stop() {
		Log.info("FlowShift Press: stop() invoked by JavaFX Platform");

		Thread forceExit = new Thread(() -> {
			try {
				Thread.sleep(SHUTDOWN_TIMEOUT_FORCE_EXIT);
				Log.warn("Shutdown timeout reached - forcing JVM exit");
				System.exit(0);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		forceExit.setDaemon(true);
		forceExit.start();

		performShutdown();
	}

	/**
	 * Performs graceful application shutdown in ordered phases.
	 */
	private void performShutdown() {
		KassandraKernel kernel = KassandraKernel.getInstance();
		ChatSatellite chat = KernelConnector.getChamber();

		stopVoiceInput(chat);

		// Only perform hibernate if not already acknowledged and chat is connected
		if (!kernel.isShutdownAcknowledged()) {
			hibernateChat(chat, kernel);
		} else {
			Log.info("Kernel shutdown already acknowledged - skipping hibernate");
		}

		kernel.writeKernelHibernateLog();
		kernel.requestShutdown();

		boolean acknowledged = kernel.awaitShutdownAcknowledgment(SHUTDOWN_TIMEOUT_ACK);
		if (acknowledged) {
			Log.info("Kassandra hibernation completed successfully");
		} else {
			Log.warn("Hibernation timeout - forcing shutdown");
		}

		cleanupResources();

		Log.info("Application shutdown complete");
		Platform.exit();
		System.exit(0);
	}

	/**
	 * Stops all active voice input on the chat controller.
	 */
	private void stopVoiceInput(ChatSatellite chat) {
		if (chat != null && chat.getController() != null) {
			chat.getController().stopAllVoiceInput();
			Log.info("Voice input stopped");
		}
	}

	/**
	 * Sends hibernate command to the chat system if connected.
	 */
	private void hibernateChat(ChatSatellite chat, KassandraKernel kernel) {
		if (chat != null && chat.getController() != null
				&& chat.getController().getConnectionState() == ChatController.ConnectionState.CONNECTED) {
			try {
				String response = kernel.think("HIBERNATE");
				Log.info("Chat hibernation response: " + (response != null ? response.length() + " chars" : "null"));
			} catch (Exception ex) {
				Log.warn("Chat hibernation failed: " + ex.getMessage());
			}
		} else {
			Log.info("Chat not connected - skipping hibernate");
		}
	}

	/**
	 * Initializes application settings from configuration file.
	 */
	private void initializeSettings() throws IOException {
		Path execPath = Paths.get(System.getProperty("user.dir"));
		this.settings = new OptionsHandler(execPath);
		this.settings.read();
	}

	/**
	 * Establishes bridge connection. Connection failure is non-fatal.
	 */
	private void connectBridge() {
		try {
			this.bridgeClient = new EditorSocketClient();
		} catch (IOException e) {
			Log.warn("BridgeClient connection failed - continuing without bridge: " + e.getMessage());
		}
	}

	/**
	 * Sets up window close request handler with graceful shutdown.
	 */
	private void setupShutdownHook(Stage stage) {
		stage.setOnCloseRequest(e -> {
			e.consume();
			new Thread(this::performShutdown).start();
		});
	}

	private void setupGlobalUncaughtExceptionHandler() {
		Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
			Log.error(throwable, "Uncaught exception on thread: " + thread.getName());
			if (Platform.isFxApplicationThread()) {
				showFatalDialogAndExit(throwable);
			} else {
				Platform.runLater(() -> showFatalDialogAndExit(throwable));
			}
		});
	}

	private static void showFatalDialogAndExit(Throwable throwable) {
		try {
			WindowUtils.showExceptionDialog(Alert.AlertType.ERROR, null, "Critical Application Error",
					"FlowShift Press: Sovereign Engine Failure",
					"An unexpected fatal error occurred. The application will now close.", throwable);
		} finally {
			System.exit(1);
		}
	}

	public void initialize(Stage stage) throws Exception {
		Log.info("Initializing FlowShift Press application");
		this.mainStage = stage;
		KernelConnector.setApp(this);

		bootstrapEnvironment(stage);
		loadAndAttachUI(stage);

		// Nur starten, wenn Kernel nicht bereits läuft (z.B. via KassandraLauncher)
		KassandraKernel kernel = KassandraKernel.getInstance();
		if (kernel.getOptions() == null) {
			KernelConnector.connect(kernel, this.settings, stage);
		} else {
			Log.info("FlowShiftPress: Kernel already active – bridge only");
			connectBridge();
		}

		finalizeStartup(stage);
	}

	private void bootstrapEnvironment(Stage stage) {
		LangMap.init();
		ToastNotifier.initialize(this.settings);
		applyConfiguration(stage);

		CustomPopupControl.addGlobalStylesheet(CSS_MAIN);
		CustomPopupControl.addGlobalStylesheet(CSS_SYNTAX);

		Log.info("Environment initialization complete");
	}

	private void loadAndAttachUI(Stage stage) throws Exception {
		URL fxmlUrl = getClass().getResource(FXML_PATH);
		if (fxmlUrl == null) {
			throw new IOException("FXML resource not found: " + FXML_PATH);
		}

		FXMLLoader loader = new FXMLLoader(fxmlUrl);
		Parent root = loader.load();

		this.controllerEditor = loader.getController();
		if (this.controllerEditor != null) {
			this.controllerEditor.setApp(this);
		}

		Scene scene = new Scene(root);
		applyStyles(root);

		stage.setTitle("FlowShift Press - Sovereign Edition");
		stage.setScene(scene);

		Log.info("UI loaded and linked successfully");
	}

	private void finalizeStartup(Stage stage) {
		stage.show();

		Platform.runLater(() -> {
			try {
				Parent root = stage.getScene().getRoot();
				root.applyCss();
				root.layout();

				double minWidth = root.minWidth(-1);
				double minHeight = root.minHeight(-1);

				if (minWidth > 0 && minHeight > 0 && minWidth < 5000 && minHeight < 5000) {
					stage.setMinWidth(minWidth + 40);
					stage.setMinHeight(minHeight + 40);
					Log.info(String.format("Window constraints set: %.0fx%.0f", minWidth, minHeight));
				} else {
					Log.warn("Invalid min sizes detected - using defaults");
					stage.setMinWidth(DEFAULT_MIN_WIDTH);
					stage.setMinHeight(DEFAULT_MIN_HEIGHT);
				}
			} catch (Exception ex) {
				Log.warn("Failed to set window constraints: " + ex.getMessage());
				stage.setMinWidth(DEFAULT_MIN_WIDTH);
				stage.setMinHeight(DEFAULT_MIN_HEIGHT);
			}
		});

		stage.toFront();
		if (stage.getScene().getRoot() != null) {
			stage.getScene().getRoot().requestFocus();
		}

		Log.info("Application startup complete - all systems nominal");
	}

	/**
	 * Toggles the chat satellite window.
	 */
	public void toggleChat() {
		ChatSatellite chat = KernelConnector.getChamber();

		if (chat != null) {
			chat.toggle();
		} else if (mainStage != null) {
			ChatSatellite newChat = new ChatSatellite(mainStage);
			KernelConnector.setChamber(newChat);
			newChat.show();
		} else {
			Log.error("Cannot create chat: mainStage is null");
		}
	}

	public ChatSatellite getResonanceChamber() {
		return KernelConnector.getChamber();
	}

	public OptionsHandler getSettings() {
		return settings;
	}

	/**
	 * Restores window geometry from persistent settings. Caps values to screen
	 * bounds to prevent off-screen placement.
	 */
	private void applyConfiguration(Stage stage) {
		Log.info("Applying window geometry configuration");

		Rectangle2D screen = Screen.getPrimary().getBounds();
		double screenW = screen.getWidth();
		double screenH = screen.getHeight();

		double width = clamp(settings.getValue(OptionKey.WINDOW_WIDTH), MIN_WINDOW_SIZE, screenW);
		double height = clamp(settings.getValue(OptionKey.WINDOW_HEIGHT), MIN_WINDOW_SIZE, screenH);
		stage.setWidth(width);
		stage.setHeight(height);

		if (!settings.isLoadedFromFile()) {
			stage.centerOnScreen();
			return;
		}

		double x = clamp(settings.getValue(OptionKey.WINDOW_X), 0, screenW - width);
		double y = clamp(settings.getValue(OptionKey.WINDOW_Y), 0, screenH - height);
		stage.setX(x);
		stage.setY(y);

		stage.addEventHandler(WindowEvent.WINDOW_SHOWN, _ -> WindowUtils.correctStagePositionWithinBounds(stage));
	}

	/**
	 * Attaches all required stylesheets to the scene root.
	 */
	private void applyStyles(Parent root) {
		for (String path : new String[] { CSS_MAIN, CSS_SYNTAX }) {
			URL url = getClass().getResource(path);
			if (url != null) {
				root.getStylesheets().add(url.toExternalForm());
			} else {
				Log.warn("Stylesheet not found: " + path);
			}
		}
	}

	/**
	 * Cleans up all resources in ordered sequence.
	 */
	private void cleanupResources() {
		closeBridge();
		cleanupController();
		closeDatabase();
		persistGeometry();
	}

	private void closeBridge() {
		if (bridgeClient != null) {
			try {
				bridgeClient.close();
				Log.info("Bridge client closed successfully");
			} catch (IOException e) {
				Log.warn("Failed to close bridge client: " + e.getMessage());
			}
		}
	}

	private void cleanupController() {
		if (controllerEditor != null) {
			controllerEditor.cleanup();
		}
	}

	private void closeDatabase() {
		avp.vault.DuckDBConnector.getInstance().shutdown();
	}

	/**
	 * Persists current window geometry to settings. Only saves if dimensions exceed
	 * minimum thresholds.
	 */
	private void persistGeometry() {
		if (mainStage == null || mainStage.isFullScreen()) {
			Log.info("Fullscreen active - geometry unchanged");
			return;
		}

		Rectangle2D screen = Screen.getPrimary().getBounds();
		double x = clamp(mainStage.getX(), 0, screen.getWidth());
		double y = clamp(mainStage.getY(), 0, screen.getHeight());
		double w = clamp(mainStage.getWidth(), MIN_WINDOW_SIZE, screen.getWidth());
		double h = clamp(mainStage.getHeight(), MIN_WINDOW_SIZE, screen.getHeight());

		// Only persist if dimensions meet minimum requirements
		if (w > MIN_WINDOW_SIZE && h > MIN_WINDOW_SIZE) {
			settings.setValue(OptionKey.WINDOW_X, (int) x);
			settings.setValue(OptionKey.WINDOW_Y, (int) y);
			settings.setValue(OptionKey.WINDOW_WIDTH, (int) w);
			settings.setValue(OptionKey.WINDOW_HEIGHT, (int) h);
			settings.write();
			Log.info(String.format("Geometry persisted: %.0fx%.0f at (%.0f,%.0f)", w, h, x, y));
		} else {
			Log.warn("Invalid geometry dimensions - not persisting");
		}
	}

	/**
	 * Clamps a value between min and max bounds.
	 */
	private static double clamp(double val, double min, double max) {
		return Math.max(min, Math.min(val, max));
	}

	/**
	 * Returns the current Markdown editor controller.
	 */
	public EditorController getController() {
		return controllerEditor;
	}

	/**
	 * Returns the main application stage.
	 */
	public Stage getMainStage() {
		return mainStage;
	}

	/**
	 * Displays the about dialog with branding and license information.
	 */
	public void showAboutDialog() {
		String title = "About FlowShift Press";
		String headline = "FlowShift Press - Sovereign Edition";
		String copyright = "(c) 2026 FlowShift. All rights reserved.";
		String licenseStatus = "Status: INTERNAL ARCHITECT VERSION (AVP-Ready)";

		String mainContent = """
				FlowShift Press is a high-performance DTP Layout Engine
				for hybrid script processing.

				Version: 1.0.0-alpha
				Architect: Henryk Daniel Zschuppan
				Coop Teammitglied: Kassandra

				This instance is connected to the Axiomatic Validation Protocol.
				""";

		WindowUtils.showAboutDialog(mainStage, title, headline, copyright, mainContent, licenseStatus);
	}
}