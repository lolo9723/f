package com.videofabrikasi.app;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;

/**
 * Android-safe localhost callback listener.
 *
 * Binding the wildcard socket lets the platform accept whichever loopback
 * address family Chrome resolves for "localhost" (IPv4 or IPv6). Accepted
 * peers are still required to be loopback, so no LAN client can complete OAuth.
 */
final class OAuthLoopbackServer {
    static final int ACCEPT_TIMEOUT_MS = 30 * 60 * 1000;

    private OAuthLoopbackServer() {}

    static ServerSocket open() throws Exception {
        SecureRandom random = new SecureRandom();
        Exception last = null;
        int range = KaggleOAuthPkce.MAX_LOOPBACK_PORT
                - KaggleOAuthPkce.MIN_LOOPBACK_PORT + 1;
        for (int i = 0; i < range; i++) {
            int port = KaggleOAuthPkce.MIN_LOOPBACK_PORT + random.nextInt(range);
            ServerSocket server = new ServerSocket();
            try {
                server.setReuseAddress(true);
                // Wildcard bind is deliberate: Android/Chrome may resolve localhost
                // to ::1 before 127.0.0.1. Peer validation below keeps it loopback-only.
                server.bind(new InetSocketAddress(port), 4);
                server.setSoTimeout(ACCEPT_TIMEOUT_MS);
                return server;
            } catch (Exception bindError) {
                last = bindError;
                try { server.close(); } catch (Exception ignored) {}
            }
        }
        throw new IllegalStateException(
                "Kaggle OAuth için boş localhost portu bulunamadı.", last);
    }

    static Socket acceptLoopback(ServerSocket server) throws Exception {
        if (server == null) throw new IllegalArgumentException("OAuth server boş.");
        while (true) {
            Socket socket = server.accept();
            if (socket.getInetAddress() != null
                    && socket.getInetAddress().isLoopbackAddress()) {
                return socket;
            }
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
}
