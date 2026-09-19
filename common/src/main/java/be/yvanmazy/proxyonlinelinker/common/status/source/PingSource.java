/*
 * MIT License
 *
 * Copyright (c) 2026 Yvan Mazy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package be.yvanmazy.proxyonlinelinker.common.status.source;

import be.yvanmazy.proxyonlinelinker.common.util.Preconditions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class PingSource implements StatusSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(PingSource.class);

    private static final byte[] STATUS_REQUEST_PACKET = {0x00};

    // Maximum JSON size accepted from the server.
    private static final int MAX_JSON_BYTE_LENGTH = 32767 * 3;

    private final String host;
    private final int port;
    private final int timeout;
    private final int protocol;
    private final Proxy proxy;

    private final byte[] handshakeDataPacket;

    public PingSource(
            final @NotNull String host,
            final int port,
            final int timeout,
            final int protocol,
            final @Nullable Proxy proxy
    ) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = Preconditions.requirePort(port);
        this.timeout = Math.max(timeout, 0);
        this.protocol = protocol;
        this.proxy = proxy;

        try {
            this.handshakeDataPacket = this.buildHandshakePacket();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to build handshake packet", e);
        }
    }

    @Override
    public int fetch() {
        try (final Socket socket = this.openSocket()) {

            // Connect to the target Minecraft server.
            socket.setSoTimeout(this.timeout);
            socket.connect(new InetSocketAddress(this.host, this.port), this.timeout);

            final DataOutputStream out =
                    new DataOutputStream(socket.getOutputStream());

            final DataInputStream in =
                    new DataInputStream(socket.getInputStream());

            /*
             * Handshake:
             * Packet ID = 0x00
             * Protocol version = configured protocol
             * Server address = target host
             * Server port = target port
             * Next state = STATUS (1)
             */
            writePacket(out, this.handshakeDataPacket);

            // Status request packet.
            writePacket(out, STATUS_REQUEST_PACKET);

            /*
             * Read the response:
             *
             * Packet Length
             * Packet ID
             * JSON String Length
             * JSON String
             */
            readVarInt(in);

            final int packetId = readVarInt(in);

            if (packetId != 0x00) {
                this.warnError("Unexpected packet ID=" + packetId);
                return -1;
            }

            final int jsonLength = readVarInt(in);

            if (jsonLength < 0 || jsonLength > MAX_JSON_BYTE_LENGTH) {
                this.warnError("Invalid JSON length=" + jsonLength);
                return -1;
            }

            final byte[] jsonBytes = in.readNBytes(jsonLength);

            if (jsonBytes.length != jsonLength) {
                this.warnError(
                        "Incomplete JSON response: expected="
                                + jsonLength
                                + ", received="
                                + jsonBytes.length
                );
                return -1;
            }

            final String json =
                    new String(jsonBytes, StandardCharsets.UTF_8);

            return parseOnlinePlayerCount(json);

        } catch (final IOException | NumberFormatException e) {
            this.warnError("Exception=" + e.getMessage());
            return -1;
        }
    }

    /**
     * Extracts the online player count from the Minecraft server status JSON.
     *
     * Example:
     *
     * {
     *   "version": {...},
     *   "players": {
     *     "max": 20,
     *     "online": 2
     *   }
     * }
     *
     * This parser intentionally does not depend on the online value being
     * followed by a comma or closing brace.
     */
    private int parseOnlinePlayerCount(final String json) {

        final String needle = "\"online\"";

        int idx = json.indexOf(needle);

        if (idx < 0) {
            this.warnError("Missing \"online\" field in server status");
            return -1;
        }

        idx += needle.length();

        /*
         * Skip whitespace and the ':' separator.
         *
         * Handles:
         *
         * "online":2
         * "online": 2
         * "online" : 2
         */
        while (idx < json.length()
                && Character.isWhitespace(json.charAt(idx))) {
            idx++;
        }

        if (idx >= json.length() || json.charAt(idx) != ':') {
            this.warnError("Invalid \"online\" field in server status");
            return -1;
        }

        idx++;

        while (idx < json.length()
                && Character.isWhitespace(json.charAt(idx))) {
            idx++;
        }

        if (idx >= json.length()) {
            this.warnError("Missing online player count");
            return -1;
        }

        /*
         * Read the integer directly.
         *
         * This stops before:
         *
         * ,
         * }
         * whitespace
         * any other JSON delimiter
         *
         * Therefore:
         *
         * "online":2}
         *
         * correctly produces:
         *
         * 2
         */
        final int start = idx;

        if (json.charAt(idx) == '-') {
            idx++;
        }

        final int digitStart = idx;

        while (idx < json.length()
                && Character.isDigit(json.charAt(idx))) {
            idx++;
        }

        if (idx == digitStart) {
            this.warnError("Invalid online player count in server status");
            return -1;
        }

        final String value = json.substring(start, idx);

        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            this.warnError("Invalid online player count: " + value);
            return -1;
        }
    }

    @Override
    public @NotNull StatusSourceType type() {
        return StatusSourceType.PING;
    }

    private void warnError(final String message) {
        LOGGER.warn("Failed to ping: {}", message);
    }

    private byte[] buildHandshakePacket() throws IOException {

        final ByteArrayOutputStream stream =
                new ByteArrayOutputStream();

        // Packet ID.
        writeVarInt(stream, 0x00);

        // Protocol version.
        writeVarInt(stream, this.protocol);

        // Server address.
        writeString(stream, this.host);

        // Server port.
        stream.write((this.port >>> 8) & 0xFF);
        stream.write(this.port & 0xFF);

        // Next state = STATUS.
        writeVarInt(stream, 1);

        return stream.toByteArray();
    }

    private Socket openSocket() {

        if (this.proxy != null) {
            return new Socket(this.proxy);
        }

        return new Socket();
    }

    private static void writePacket(
            final DataOutputStream out,
            final byte[] payload
    ) throws IOException {

        writeVarInt(out, payload.length);
        out.write(payload);
    }

    private static void writeString(
            final OutputStream out,
            final String s
    ) throws IOException {

        final byte[] data = s.getBytes(StandardCharsets.UTF_8);

        writeVarInt(out, data.length);
        out.write(data);
    }

    private static void writeVarInt(
            final OutputStream out,
            int value
    ) throws IOException {

        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }

        out.write(value);
    }

    private static void writeVarInt(
            final DataOutputStream out,
            final int value
    ) throws IOException {

        writeVarInt((OutputStream) out, value);
    }

    private static int readVarInt(
            final InputStream in
    ) throws IOException {

        int numRead = 0;
        int result = 0;
        int read;

        do {
            read = in.read();

            if (read == -1) {
                throw new EOFException("VarInt truncated");
            }

            result |= (read & 0x7F) << (7 * numRead++);

            if (numRead > 5) {
                throw new IOException("VarInt too long");
            }

        } while ((read & 0x80) != 0);

        return result;
    }
}