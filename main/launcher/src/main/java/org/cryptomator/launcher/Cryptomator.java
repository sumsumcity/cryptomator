/*******************************************************************************
 * Copyright (c) 2017 Skymatic UG (haftungsbeschränkt).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE file.
 *******************************************************************************/
package org.cryptomator.launcher;

import javafx.application.Platform;
import org.apache.commons.lang3.SystemUtils;
import org.cryptomator.logging.DebugMode;
import org.cryptomator.logging.LoggerConfiguration;
import org.cryptomator.ui.FxApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

@Singleton
public class Cryptomator {

	// DaggerCryptomatorComponent gets generated by Dagger.
	// Run Maven and include target/generated-sources/annotations in your IDE.
	private static final CryptomatorComponent CRYPTOMATOR_COMPONENT = DaggerCryptomatorComponent.create();
	private static final Logger LOG = LoggerFactory.getLogger(Cryptomator.class);

	private final LoggerConfiguration logConfig;
	private final DebugMode debugMode;
	private final IpcFactory ipcFactory;
	private final Optional<String> applicationVersion;
	private final CountDownLatch shutdownLatch;
	private final CleanShutdownPerformer shutdownPerformer;

	@Inject
	Cryptomator(LoggerConfiguration logConfig, DebugMode debugMode, IpcFactory ipcFactory, @Named("applicationVersion") Optional<String> applicationVersion, @Named("shutdownLatch") CountDownLatch shutdownLatch, CleanShutdownPerformer shutdownPerformer) {
		this.logConfig = logConfig;
		this.debugMode = debugMode;
		this.ipcFactory = ipcFactory;
		this.applicationVersion = applicationVersion;
		this.shutdownLatch = shutdownLatch;
		this.shutdownPerformer = shutdownPerformer;
	}

	public static void main(String[] args) {
		int exitCode = CRYPTOMATOR_COMPONENT.application().run(args);
		System.exit(exitCode); // end remaining non-daemon threads.
	}

	/**
	 * Main entry point of the application launcher.
	 *
	 * @param args The arguments passed to this program via {@link #main(String[])}.
	 * @return Nonzero exit code in case of an error.
	 */
	private int run(String[] args) {
		logConfig.init();
		LOG.info("Starting Cryptomator {} on {} {} ({})", applicationVersion.orElse("SNAPSHOT"), SystemUtils.OS_NAME, SystemUtils.OS_VERSION, SystemUtils.OS_ARCH);
		debugMode.initialize();

		/*
		 * Attempts to create an IPC connection to a running Cryptomator instance and sends it the given args.
		 * If no external process could be reached, the args will be handled by the loopback IPC endpoint.
		 */
		try (IpcFactory.IpcEndpoint endpoint = ipcFactory.create()) {
			endpoint.getRemote().handleLaunchArgs(args); // if we are the server, getRemote() returns self.
			if (endpoint.isConnectedToRemote()) {
				LOG.info("Found running application instance. Shutting down...");
				return 2;
			} else {
				LOG.debug("Did not find running application instance. Launching GUI...");
				return runGuiApplication();
			}
		} catch (IOException e) {
			LOG.error("Failed to initiate inter-process communication.", e);
			return runGuiApplication();
		}
	}

	/**
	 * Launches the JavaFX application and waits until shutdown is requested.
	 *
	 * @return Nonzero exit code in case of an error.
	 * @implNote This method blocks until {@link #shutdownLatch} reached zero.
	 */
	private int runGuiApplication() {
		try {
			shutdownPerformer.registerShutdownHook();
			CRYPTOMATOR_COMPONENT.fxApplicationComponent().start();
			shutdownLatch.await();
			LOG.info("UI shut down");
			return 0;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return 1;
		}
	}


}
