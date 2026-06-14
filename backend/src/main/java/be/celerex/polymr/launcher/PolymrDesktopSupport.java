/*
* Copyright (C) 2026 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.celerex.polymr.launcher;

import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

final class PolymrDesktopSupport {
	private PolymrDesktopSupport() {}

	static void start(String port) {
		Thread browserThread = new Thread(() -> openBrowserWhenReady(port), "polymr-browser-opener");
		browserThread.setDaemon(true);
		browserThread.start();
		installTrayIfSupported(port);
	}

	private static void openBrowserWhenReady(String port) {
		if (!Desktop.isDesktopSupported()) {
			return;
		}
		String url = "http://127.0.0.1:" + port + "/";
		try {
			for (int attempt = 0; attempt < 60; attempt++) {
				if (isPortOpen(port)) {
					Desktop.getDesktop().browse(URI.create(url));
					return;
				}
				Thread.sleep(500L);
			}
			System.err.println("Polymr launcher could not detect a running server on port " + port + " to open the browser.");
		}
		catch (IOException e) {
			System.err.println("Polymr launcher could not open the browser: " + e.getMessage());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.err.println("Polymr launcher browser wait interrupted.");
		}
	}

	private static void installTrayIfSupported(String port) {
		if (!SystemTray.isSupported()) {
			return;
		}
		try {
			SystemTray tray = SystemTray.getSystemTray();
			PopupMenu menu = new PopupMenu();
			MenuItem openItem = new MenuItem("Open Polymr");
			openItem.addActionListener((event) -> openBrowser("http://127.0.0.1:" + port + "/"));
			menu.add(openItem);
			MenuItem quitItem = new MenuItem("Quit Polymr");
			quitItem.addActionListener((event) -> System.exit(0));
			menu.add(quitItem);
			TrayIcon trayIcon = new TrayIcon(createTrayImage(), "Polymr", menu);
			trayIcon.setImageAutoSize(true);
			tray.add(trayIcon);
		}
		catch (AWTException e) {
			System.err.println("Polymr launcher could not install the system tray icon: " + e.getMessage());
		}
	}

	public static void openBrowser(String url) {
		if (!Desktop.isDesktopSupported()) {
			return;
		}
		try {
			Desktop.getDesktop().browse(URI.create(url));
		}
		catch (IOException e) {
			System.err.println("Polymr launcher could not open the browser: " + e.getMessage());
		}
	}

	private static boolean isPortOpen(String port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 500);
			return true;
		}
		catch (IOException | NumberFormatException e) {
			return false;
		}
	}

	private static Image createTrayImage() {
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(
			0,
			0,
			image.getWidth(),
			image.getHeight(),
			new int[image.getWidth() * image.getHeight()],
			0,
			image.getWidth()
		);
		return Toolkit.getDefaultToolkit().createImage(image.getSource());
	}
}
