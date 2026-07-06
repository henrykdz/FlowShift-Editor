package com.flowshift.editor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import javafx.application.Application;
import utils.logging.Log;

/**
 * Der Bootstrapper für FlowShift Press. Behandelt Single-Instance-Lock und JVM-Parameter vor dem UI-Start.
 */
public class Launcher {

	private static final int    LOCK_PORT = 59877;
	private static ServerSocket lockSocket;

	public static void main(String[] args) {
		// 1. JVM-Optimierungen für Text-Rendering (Sovereign Quality)
		System.setProperty("prism.lcdtext", "true");
		System.setProperty("prism.text", "t2k");

		Log.info("FlowShift Press: Starting Launcher...");

		// 2. Single Instance Lock prüfen
		if (!acquireSocketLock()) {
			Log.warn("Another instance is already active. Termination initiated.");
			System.exit(0);
			return;
		}

		// 3. Übergabe an den JavaFX-Lifecycle
		Application.launch(FlowShiftEditorApp.class, args);
	}

	private static boolean acquireSocketLock() {
		try {
			lockSocket = new ServerSocket(LOCK_PORT, 0, InetAddress.getByName("127.0.0.1"));
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	public static void releaseLock() {
		try {
			if (lockSocket != null) {
				lockSocket.close();
				Log.info("Sovereign Lock released.");
			}
		} catch (IOException ignored) {
		}
	}
}
