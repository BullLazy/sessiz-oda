"use strict";

const http = require("node:http");
const crypto = require("node:crypto");
const { TextDecoder } = require("node:util");

const WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });
const PROTOCOL_VERSION = 2;

function boundedInteger(value, fallback, minimum, maximum) {
    const parsed = Number.parseInt(String(value ?? ""), 10);
    if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
        return fallback;
    }
    return parsed;
}

function createRelayServer(options = {}) {
    const maxRoomSize = boundedInteger(options.maxRoomSize ?? process.env.MAX_ROOM_SIZE, 10, 2, 50);
    const maxConnections = boundedInteger(options.maxConnections ?? process.env.MAX_CONNECTIONS, 50, 3, 500);
    const maxFrameBytes = boundedInteger(options.maxFrameBytes, 65_536, 4_096, 262_144);
    const heartbeatMs = boundedInteger(options.heartbeatMs, 25_000, 1_000, 120_000);
    const joinTimeoutMs = boundedInteger(options.joinTimeoutMs, 8_000, 1_000, 30_000);
    const rooms = new Map();
    const clients = new Set();

    const server = http.createServer((request, response) => {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");

        let pathname = "/";
        try {
            pathname = new URL(request.url, "http://localhost").pathname;
        } catch {
            response.writeHead(400, { "Content-Type": "text/plain; charset=utf-8" });
            response.end("Bad request");
            return;
        }

        if (request.method === "GET" && pathname === "/health") {
            response.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
            response.end('{"status":"ok","protocol":2,"media":true}');
            return;
        }

        response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
        response.end("Not found");
    });

    server.keepAliveTimeout = 5_000;
    server.headersTimeout = 7_000;

    server.on("upgrade", (request, socket, head) => {
        if (clients.size >= maxConnections) {
            rejectUpgrade(socket, 503, "Service Unavailable");
            return;
        }

        let pathname;
        try {
            pathname = new URL(request.url, "http://localhost").pathname;
        } catch {
            rejectUpgrade(socket, 400, "Bad Request");
            return;
        }

        const upgrade = String(request.headers.upgrade ?? "").toLowerCase();
        const connection = String(request.headers.connection ?? "").toLowerCase();
        const version = String(request.headers["sec-websocket-version"] ?? "");
        const key = String(request.headers["sec-websocket-key"] ?? "");
        const validKey = /^[A-Za-z0-9+/]{22}==$/.test(key) && Buffer.from(key, "base64").length === 16;

        if (
            request.method !== "GET" ||
            pathname !== "/chat" ||
            upgrade !== "websocket" ||
            !connection.split(",").some((value) => value.trim() === "upgrade") ||
            version !== "13" ||
            !validKey
        ) {
            rejectUpgrade(socket, 400, "Bad Request");
            return;
        }

        const accept = crypto
            .createHash("sha1")
            .update(key + WEBSOCKET_GUID)
            .digest("base64");
        socket.write([
            "HTTP/1.1 101 Switching Protocols",
            "Upgrade: websocket",
            "Connection: Upgrade",
            `Sec-WebSocket-Accept: ${accept}`,
            "Cache-Control: no-store",
            "\r\n"
        ].join("\r\n"));
        socket.setNoDelay(true);
        socket.setTimeout(0);

        const client = {
            socket,
            buffer: Buffer.alloc(0),
            joined: false,
            roomKey: null,
            alive: true,
            closed: false,
            mediaCapable: false,
            fragmentOpcode: null,
            fragments: [],
            fragmentBytes: 0,
            rateWindowStartedAt: Date.now(),
            rateCount: 0,
            mediaWindowStartedAt: Date.now(),
            mediaBytes: 0,
            mediaFrames: 0,
            joinTimer: null
        };
        clients.add(client);

        client.joinTimer = setTimeout(() => {
            if (!client.joined) {
                sendJson(client, { type: "error", code: "join_required" });
                closeClient(client, 1008, "Join required");
            }
        }, joinTimeoutMs);
        client.joinTimer.unref();

        socket.on("data", (chunk) => handleSocketData(client, chunk));
        socket.on("close", () => cleanupClient(client));
        socket.on("error", () => cleanupClient(client));

        if (head && head.length > 0) {
            handleSocketData(client, head);
        }
    });

    server.on("clientError", (_error, socket) => {
        socket.destroy();
    });

    const heartbeat = setInterval(() => {
        for (const client of clients) {
            if (!client.alive) {
                client.socket.destroy();
                cleanupClient(client);
                continue;
            }
            client.alive = false;
            sendFrame(client, 0x9, Buffer.alloc(0));
        }
    }, heartbeatMs);
    heartbeat.unref();

    function handleSocketData(client, chunk) {
        if (client.closed) {
            return;
        }
        client.alive = true;
        client.buffer = Buffer.concat([client.buffer, chunk]);
        if (client.buffer.length > maxFrameBytes * 2) {
            closeClient(client, 1009, "Frame too large");
            return;
        }

        try {
            while (true) {
                const frame = readClientFrame(client.buffer, maxFrameBytes);
                if (frame === null) {
                    return;
                }
                client.buffer = client.buffer.subarray(frame.bytesConsumed);
                handleFrame(client, frame);
                if (client.closed) {
                    return;
                }
            }
        } catch {
            closeClient(client, 1002, "Protocol error");
        }
    }

    function handleFrame(client, frame) {
        if (frame.opcode >= 0x8) {
            if (!frame.fin || frame.payload.length > 125) {
                closeClient(client, 1002, "Invalid control frame");
                return;
            }
            switch (frame.opcode) {
            case 0x8:
                sendFrame(client, 0x8, frame.payload.subarray(0, 125));
                client.socket.end();
                cleanupClient(client);
                break;
            case 0x9:
                sendFrame(client, 0xA, frame.payload);
                break;
            case 0xA:
                client.alive = true;
                break;
            default:
                closeClient(client, 1003, "Unsupported frame");
                break;
            }
            return;
        }

        if (frame.opcode === 0x0) {
            if (client.fragmentOpcode === null) {
                closeClient(client, 1002, "Unexpected continuation");
                return;
            }
            client.fragments.push(frame.payload);
            client.fragmentBytes += frame.payload.length;
            if (client.fragmentBytes > maxFrameBytes) {
                closeClient(client, 1009, "Message too large");
                return;
            }
            if (frame.fin) {
                const opcode = client.fragmentOpcode;
                const payload = Buffer.concat(client.fragments, client.fragmentBytes);
                client.fragmentOpcode = null;
                client.fragments = [];
                client.fragmentBytes = 0;
                handleDataFrame(client, opcode, payload);
            }
            return;
        }

        if (frame.opcode !== 0x1 && frame.opcode !== 0x2) {
            closeClient(client, 1003, "Unsupported frame");
            return;
        }
        if (client.fragmentOpcode !== null) {
            closeClient(client, 1002, "Fragment already open");
            return;
        }
        if (frame.fin) {
            handleDataFrame(client, frame.opcode, frame.payload);
            return;
        }
        client.fragmentOpcode = frame.opcode;
        client.fragments = [frame.payload];
        client.fragmentBytes = frame.payload.length;
    }

    function handleDataFrame(client, opcode, payload) {
        if (opcode !== 0x1) {
            closeClient(client, 1003, "Binary messages unsupported");
            return;
        }
        let text;
        try {
            text = UTF8_DECODER.decode(payload);
        } catch {
            closeClient(client, 1007, "Invalid UTF-8");
            return;
        }
        handleTextMessage(client, text);
    }

    function handleTextMessage(client, text) {
        if (text.length > 40_000) {
            closeClient(client, 1009, "Message too large");
            return;
        }

        let message;
        try {
            message = JSON.parse(text);
        } catch {
            closeClient(client, 1007, "Invalid JSON");
            return;
        }

        if (!client.joined) {
            if (
                message.type !== "join" ||
                typeof message.room !== "string" ||
                typeof message.proof !== "string" ||
                !/^[a-f0-9]{64}$/.test(message.room) ||
                !/^[a-f0-9]{64}$/.test(message.proof)
            ) {
                sendJson(client, { type: "error", code: "join_required" });
                closeClient(client, 1008, "Join required");
                return;
            }

            const roomKey = `${message.room}.${message.proof}`;
            let room = rooms.get(roomKey);
            if (!room) {
                room = new Set();
                rooms.set(roomKey, room);
            }
            if (room.size >= maxRoomSize) {
                if (room.size === 0) {
                    rooms.delete(roomKey);
                }
                sendJson(client, { type: "error", code: "room_full" });
                closeClient(client, 1008, "Room full");
                return;
            }

            clearTimeout(client.joinTimer);
            client.joinTimer = null;
            client.joined = true;
            client.roomKey = roomKey;
            client.mediaCapable = message.media === 1;
            room.add(client);
            sendJson(client, { type: "joined", protocol: PROTOCOL_VERSION, media: 1 });
            broadcastPresence(room);
            return;
        }

        if (message.type !== "cipher" || typeof message.payload !== "string") {
            closeClient(client, 1008, "Invalid message");
            return;
        }

        const kind = message.kind ?? "text";
        if (kind !== "text" && kind !== "media") {
            closeClient(client, 1008, "Invalid payload");
            return;
        }

        const now = Date.now();
        if (kind === "text") {
            if (now - client.rateWindowStartedAt >= 10_000) {
                client.rateWindowStartedAt = now;
                client.rateCount = 0;
            }
            client.rateCount += 1;
            if (client.rateCount > 15) {
                sendJson(client, { type: "error", code: "rate_limited" });
                closeClient(client, 1008, "Rate limited");
                return;
            }
        } else {
            if (!client.mediaCapable) {
                closeClient(client, 1008, "Media capability required");
                return;
            }
            if (now - client.mediaWindowStartedAt >= 180_000) {
                client.mediaWindowStartedAt = now;
                client.mediaBytes = 0;
                client.mediaFrames = 0;
            }
            client.mediaBytes += Buffer.byteLength(text, "utf8");
            client.mediaFrames += 1;
            if (client.mediaBytes > 48 * 1024 * 1024 || client.mediaFrames > 2_500) {
                sendJson(client, { type: "error", code: "rate_limited" });
                closeClient(client, 1008, "Media rate limited");
                return;
            }
        }

        const payloadLimit = kind === "media" ? 32_000 : 12_000;
        if (
            message.payload.length < 24 ||
            message.payload.length > payloadLimit ||
            !/^[A-Za-z0-9+/]+={0,2}$/.test(message.payload)
        ) {
            closeClient(client, 1008, "Invalid payload");
            return;
        }

        const room = rooms.get(client.roomKey);
        if (!room) {
            closeClient(client, 1011, "Room unavailable");
            return;
        }
        const outgoing = { type: "cipher", kind, payload: message.payload };
        if (kind === "media") {
            broadcastMedia(room, outgoing);
        } else {
            broadcast(room, outgoing);
        }
    }

    function broadcastPresence(room) {
        broadcast(room, { type: "presence", count: room.size });
    }

    function broadcast(room, message) {
        const encoded = JSON.stringify(message);
        for (const member of room) {
            sendFrame(member, 0x1, Buffer.from(encoded, "utf8"));
        }
    }

    function broadcastMedia(room, message) {
        const encoded = Buffer.from(JSON.stringify(message), "utf8");
        for (const member of room) {
            if (member.mediaCapable) {
                sendFrame(member, 0x1, encoded);
            }
        }
    }

    function sendJson(client, message) {
        sendFrame(client, 0x1, Buffer.from(JSON.stringify(message), "utf8"));
    }

    function sendFrame(client, opcode, payload) {
        if (client.closed || !client.socket.writable) {
            return;
        }
        try {
            client.socket.write(encodeServerFrame(opcode, payload));
        } catch {
            client.socket.destroy();
            cleanupClient(client);
        }
    }

    function closeClient(client, code, reason) {
        if (client.closed) {
            return;
        }
        const reasonBytes = Buffer.from(reason, "utf8").subarray(0, 123);
        const payload = Buffer.alloc(2 + reasonBytes.length);
        payload.writeUInt16BE(code, 0);
        reasonBytes.copy(payload, 2);
        if (client.socket.writable) {
            try {
                client.socket.end(encodeServerFrame(0x8, payload));
            } catch {
                client.socket.destroy();
            }
        } else {
            client.socket.destroy();
        }
        cleanupClient(client);
    }

    function cleanupClient(client) {
        if (client.closed) {
            return;
        }
        client.closed = true;
        if (client.joinTimer) {
            clearTimeout(client.joinTimer);
            client.joinTimer = null;
        }
        clients.delete(client);
        client.buffer = Buffer.alloc(0);
        client.fragments = [];
        client.fragmentBytes = 0;
        client.fragmentOpcode = null;

        if (client.roomKey) {
            const room = rooms.get(client.roomKey);
            if (room) {
                room.delete(client);
                if (room.size === 0) {
                    rooms.delete(client.roomKey);
                } else {
                    broadcastPresence(room);
                }
            }
            client.roomKey = null;
        }
    }

    server.closeAll = () => {
        clearInterval(heartbeat);
        for (const client of [...clients]) {
            client.socket.destroy();
            cleanupClient(client);
        }
        rooms.clear();
    };

    return server;
}

function readClientFrame(buffer, maxFrameBytes) {
    if (buffer.length < 2) {
        return null;
    }
    const first = buffer[0];
    const second = buffer[1];
    if ((first & 0x70) !== 0 || (second & 0x80) === 0) {
        throw new Error("Invalid frame");
    }

    const fin = (first & 0x80) !== 0;
    const opcode = first & 0x0f;
    let length = second & 0x7f;
    let offset = 2;

    if (length === 126) {
        if (buffer.length < 4) {
            return null;
        }
        length = buffer.readUInt16BE(2);
        offset = 4;
    } else if (length === 127) {
        if (buffer.length < 10) {
            return null;
        }
        const wideLength = buffer.readBigUInt64BE(2);
        if (wideLength > BigInt(maxFrameBytes)) {
            throw new Error("Frame too large");
        }
        length = Number(wideLength);
        offset = 10;
    }

    if (length > maxFrameBytes || buffer.length < offset + 4 + length) {
        if (length > maxFrameBytes) {
            throw new Error("Frame too large");
        }
        return null;
    }

    const mask = buffer.subarray(offset, offset + 4);
    offset += 4;
    const payload = Buffer.allocUnsafe(length);
    for (let index = 0; index < length; index += 1) {
        payload[index] = buffer[offset + index] ^ mask[index % 4];
    }
    return { fin, opcode, payload, bytesConsumed: offset + length };
}

function encodeServerFrame(opcode, payload) {
    const length = payload.length;
    let header;
    if (length < 126) {
        header = Buffer.from([0x80 | opcode, length]);
    } else if (length <= 0xffff) {
        header = Buffer.alloc(4);
        header[0] = 0x80 | opcode;
        header[1] = 126;
        header.writeUInt16BE(length, 2);
    } else {
        header = Buffer.alloc(10);
        header[0] = 0x80 | opcode;
        header[1] = 127;
        header.writeBigUInt64BE(BigInt(length), 2);
    }
    return Buffer.concat([header, payload]);
}

function rejectUpgrade(socket, statusCode, statusText) {
    if (socket.writable) {
        socket.end(
            `HTTP/1.1 ${statusCode} ${statusText}\r\n` +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Length: 0\r\n\r\n"
        );
    } else {
        socket.destroy();
    }
}

if (require.main === module) {
    const port = boundedInteger(process.env.PORT, 8080, 1, 65_535);
    const relay = createRelayServer();
    relay.once("error", () => process.exit(1));
    relay.listen(port, "0.0.0.0");

    const stop = () => {
        relay.closeAll();
        relay.close(() => process.exit(0));
        setTimeout(() => process.exit(0), 2_000).unref();
    };
    process.once("SIGTERM", stop);
    process.once("SIGINT", stop);
}

module.exports = { createRelayServer };
