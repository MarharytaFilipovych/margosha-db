package com.margosha.node.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class HangingServer implements AutoCloseable {

	private final ServerSocket socket;
	private final List<Socket> connections = new CopyOnWriteArrayList<>();

	private HangingServer(ServerSocket socket) {
		this.socket = socket;
		Thread.ofVirtual().name("hanging-server").start(this::acceptAll);
	}

	public static HangingServer start() {
		try {
			return new HangingServer(new ServerSocket(0));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public String address() {
		return TestData.localAddress(socket.getLocalPort());
	}

	public int connections() {
		return connections.size();
	}

	private void acceptAll() {
		while (!socket.isClosed()) {
			try {
				connections.add(socket.accept());
			} catch (IOException closed) {
				return;
			}
		}
	}

	@Override
	public void close() {
		try {
			socket.close();
			for (Socket connection : connections) {
				connection.close();
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
