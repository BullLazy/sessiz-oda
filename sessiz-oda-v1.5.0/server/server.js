"use strict";

const http = require("node:http");
const crypto = require("node:crypto");
const { TextDecoder } = require("node:util");

const WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });
const PROTOCOL_VERSION = 4;

function boundedInteger(value, fallback, minimum, maximum) {
    const parsed = Number.parseInt(String(value ?? ""), 10);
    if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
        return fallback;
    }
    return parsed;
}

function createRelayServer(options = {}) {
    const maxRoomSize = boundedInteger(
        options.maxRoomSize ?? process.env.MAX_ROOM_SIZE,
        50,
        2,
        100
    );
    const maxConnections = boundedInteger(
        options.maxConnections ?? process.env.MAX_CONNECTIONS,
        500,
        3,
        2_000
    );
    const maxFrameBytes = boundedInteger(options.maxFrameBytes, 65_536, 4_096, 262_144);
    const heartbeatMs = boundedInteger(options.heartbeatMs, 15_000, 1_000, 120_000);
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
            response.end(JSON.stringify({
                status: "ok",
                protocol: PROTOCOL_VERSION,
                media: true,
                notifications: true,
                receipts: true,
                replies: true,
                viewOnce: true
            }));
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
        const validKey =
            /^[A-Za-z0-9+/]{22}==$/.test(key) &&
            Buffer.from(key, "base64").length === 16;

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
            mode: null,
            roomKey: null,
            roomId: null,
            watchRoomKeys: new Set(),
            clientId: null,
            alive: true,
            closed: false,
            cleaned: false,
            mediaCapable: false,
            fragmentOpcode: null,
            fragments: [],
            fragmentBytes: 0,
            waitingForDrain: new Set(),
            drainSources: new Set(),
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
        socket.on("drain", () => releaseDrainTarget(client));
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
            if (client.waitingForDrain.size > 0) {
                client.alive = true;
                continue;
            }
            if (!client.alive) {
                closeClient(client, 1001, "Heartbeat timeout");
                continue;
            }
            client.alive = false;
            sendFrame(client, 0x9, Buffer.alloc(0), null);
        }
    }, heartbeatMs);
    heartbeat.unref();

    function handleSocketData(client, chunk) {
        if (client.closed || !Buffer.isBuffer(chunk)) {
            return;
        }
        client.alive = true;
        if (client.buffer.length + chunk.length > maxFrameBytes * 2 + 32) {
            closeClient(client, 1009, "Frame too large");
            return;
        }
        client.buffer = client.buffer.length === 0
            ? chunk
            : Buffer.concat([client.buffer, chunk]);

        while (!client.closed) {
            if (client.buffer.length < 2) {
                return;
            }
            const first = client.buffer[0];
            const second = client.buffer[1];
            const fin = (first & 0x80) !== 0;
            const opcode = first & 0x0f;
            const masked = (second & 0x80) !== 0;
            let payloadLength = second & 0x7f;
            let offset = 2;

            if ((first & 0x70) !== 0 || !masked) {
                closeClient(client, 1002, "Invalid frame");
                return;
            }
            if (payloadLength === 126) {
                if (client.buffer.length < 4) {
                    return;
                }
                payloadLength = client.buffer.readUInt16BE(2);
                offset = 4;
            } else if (payloadLength === 127) {
                if (client.buffer.length < 10) {
                    return;
                }
                const longLength = client.buffer.readBigUInt64BE(2);
                if (longLength > BigInt(maxFrameBytes)) {
                    closeClient(client, 1009, "Frame too large");
                    return;
                }
                payloadLength = Number(longLength);
                offset = 10;
            }
            if (
                payloadLength > maxFrameBytes ||
                ((opcode & 0x08) !== 0 && (!fin || payloadLength > 125))
            ) {
                closeClient(client, 1009, "Frame too large");
                return;
            }
            const totalLength = offset + 4 + payloadLength;
            if (client.buffer.length < totalLength) {
                return;
            }
            const mask = client.buffer.subarray(offset, offset + 4);
            const payload = Buffer.from(
                client.buffer.subarray(offset + 4, totalLength)
            );
            for (let index = 0; index < payload.length; index += 1) {
                payload[index] ^= mask[index & 3];
            }
            client.buffer = client.buffer.subarray(totalLength);
            handleFrame(client, fin, opcode, payload);
        }
    }

    function handleFrame(client, fin, opcode, payload) {
        if (opcode === 0x8) {
            closeClient(client, 1000, "Closed");
            return;
        }
        if (opcode === 0x9) {
            sendFrame(client, 0xA, payload, null);
            return;
        }
        if (opcode === 0xA) {
            client.alive = true;
            return;
        }
        if (opcode === 0x2) {
            closeClient(client, 1003, "Binary unsupported");
            return;
        }
        if (opcode === 0x0) {
            if (client.fragmentOpcode === null) {
                closeClient(client, 1002, "Unexpected continuation");
                return;
            }
            client.fragments.push(payload);
            client.fragmentBytes += payload.length;
            if (client.fragmentBytes > maxFrameBytes) {
                closeClient(client, 1009, "Message too large");
                return;
            }
            if (!fin) {
                return;
            }
            const complete = Buffer.concat(client.fragments, client.fragmentBytes);
            const originalOpcode = client.fragmentOpcode;
            client.fragmentOpcode = null;
            client.fragments = [];
            client.fragmentBytes = 0;
            if (originalOpcode === 0x1) {
                handleTextPayload(client, complete);
            }
            return;
        }
        if (opcode !== 0x1) {
            closeClient(client, 1002, "Unknown opcode");
            return;
        }
        if (!fin) {
            if (client.fragmentOpcode !== null) {
                closeClient(client, 1002, "Nested fragments");
                return;
            }
            client.fragmentOpcode = opcode;
            client.fragments = [payload];
            client.fragmentBytes = payload.length;
            return;
        }
        if (client.fragmentOpcode !== null) {
            closeClient(client, 1002, "Invalid fragment");
            return;
        }
        handleTextPayload(client, payload);
    }

    function handleTextPayload(client, payload) {
        let text;
        try {
            text = UTF8_DECODER.decode(payload);
        } catch {
            closeClient(client, 1007, "Invalid UTF-8");
            return;
        }
        let message;
        try {
            message = JSON.parse(text);
        } catch {
            sendJson(client, { type: "error", code: "invalid_message" });
            return;
        }
        if (!message || typeof message !== "object" || Array.isArray(message)) {
            sendJson(client, { type: "error", code: "invalid_message" });
            return;
        }
        if (!client.joined) {
            if (message.type === "join") {
                joinMember(client, message);
            } else if (message.type === "monitor") {
                joinMonitor(client, message);
            } else {
                sendJson(client, { type: "error", code: "join_required" });
            }
            return;
        }
        if (client.mode !== "member" || message.type !== "cipher") {
            sendJson(client, { type: "error", code: "invalid_message" });
            return;
        }
        relayCipher(client, message);
    }

    function joinMember(client, message) {
        const roomId = String(message.room ?? "");
        const proof = String(message.proof ?? "");
        const clientId = String(message.client ?? "");
        const mediaCapable = message.media === 1;
        if (
            !validHex(roomId, 64) ||
            !validHex(proof, 64) ||
            !validHex(clientId, 32)
        ) {
            sendJson(client, { type: "error", code: "join_required" });
            closeClient(client, 1008, "Invalid join");
            return;
        }
        const roomKey = keyFor(roomId, proof);
        let room = rooms.get(roomKey);
        if (!room) {
            room = { roomId, proof, members: new Set() };
            rooms.set(roomKey, room);
        }

        let replaced = null;
        for (const member of room.members) {
            if (member.clientId === clientId) {
                replaced = member;
                break;
            }
        }
        if (replaced) {
            sendJson(replaced, { type: "error", code: "session_replaced" });
            closeClient(replaced, 4000, "Session replaced");
            if (!rooms.has(roomKey)) {
                rooms.set(roomKey, room);
            }
        }
        if (room.members.size >= maxRoomSize) {
            sendJson(client, { type: "error", code: "room_full" });
            closeClient(client, 1008, "Room full");
            if (room.members.size === 0) {
                rooms.delete(roomKey);
            }
            return;
        }

        clearTimeout(client.joinTimer);
        client.joinTimer = null;
        client.joined = true;
        client.mode = "member";
        client.roomKey = roomKey;
        client.roomId = roomId;
        client.clientId = clientId;
        client.mediaCapable = mediaCapable;
        room.members.add(client);
        sendJson(client, {
            type: "joined",
            mode: "member",
            protocol: PROTOCOL_VERSION,
            media: 1,
            notifications: 1,
            receipts: 1,
            replies: 1,
            viewOnce: 1
        });
        broadcastPresence(room);
    }

    function joinMonitor(client, message) {
        const clientId = String(message.client ?? "");
        const subscriptions = Array.isArray(message.rooms) ? message.rooms : [];
        if (
            !validHex(clientId, 32) ||
            subscriptions.length === 0 ||
            subscriptions.length > 30
        ) {
            sendJson(client, { type: "error", code: "join_required" });
            closeClient(client, 1008, "Invalid monitor");
            return;
        }
        const watched = new Set();
        for (const subscription of subscriptions) {
            const roomId = String(subscription?.room ?? "");
            const proof = String(subscription?.proof ?? "");
            if (!validHex(roomId, 64) || !validHex(proof, 64)) {
                sendJson(client, { type: "error", code: "join_required" });
                closeClient(client, 1008, "Invalid monitor");
                return;
            }
            watched.add(keyFor(roomId, proof));
        }
        clearTimeout(client.joinTimer);
        client.joinTimer = null;
        client.joined = true;
        client.mode = "monitor";
        client.clientId = clientId;
        client.watchRoomKeys = watched;
        sendJson(client, {
            type: "joined",
            mode: "monitor",
            protocol: PROTOCOL_VERSION,
            notifications: 1
        });
    }

    function relayCipher(source, message) {
        const room = rooms.get(source.roomKey);
        if (!room || !room.members.has(source)) {
            sendJson(source, { type: "error", code: "join_required" });
            return;
        }

        const kind = message.kind === undefined ? "text" : String(message.kind);
        const payload = typeof message.payload === "string" ? message.payload : "";
        const stage = message.stage === undefined ? null : String(message.stage);
        const messageId = message.id === undefined ? null : String(message.id);
        const payloadLimit = kind === "media" ? 32_000 : 12_000;

        const validKind = kind === "text" || kind === "media" || kind === "receipt";
        const validStage =
            (kind === "text" && stage === null) ||
            (kind === "receipt" && stage === null) ||
            (kind === "media" && (stage === "start" || stage === "chunk" || stage === "end"));
        const acceptsId =
            (kind === "text" && stage === null) ||
            kind === "media";
        const acknowledgesId =
            (kind === "text" && stage === null) ||
            (kind === "media" && stage === "start");
        if (
            !validKind ||
            !validStage ||
            payload.length < 16 ||
            payload.length > payloadLimit ||
            (messageId !== null && (!acceptsId || !validHex(messageId, 32)))
        ) {
            sendJson(source, { type: "error", code: "invalid_message" });
            return;
        }

        const targets = [];
        for (const target of room.members) {
            if (kind === "media" && !target.mediaCapable) {
                continue;
            }
            if (messageId !== null && target === source) {
                continue;
            }
            targets.push(target);
        }

        if (messageId !== null && acknowledgesId) {
            const recipients = targets.filter((target) => target !== source).length;
            sendJson(source, {
                type: "accepted",
                id: messageId,
                recipients
            });
        }

        const outgoing = { type: "cipher", kind, payload };
        if (stage !== null) {
            outgoing.stage = stage;
        }
        const frame = encodeFrame(0x1, Buffer.from(JSON.stringify(outgoing), "utf8"));
        for (const target of targets) {
            sendEncodedFrame(target, frame, source);
        }

        if (kind === "text" || (kind === "media" && stage === "start")) {
            notifyMonitors(source);
        }
    }

    function notifyMonitors(source) {
        for (const monitor of clients) {
            if (
                monitor.mode === "monitor" &&
                monitor.clientId !== source.clientId &&
                monitor.watchRoomKeys.has(source.roomKey)
            ) {
                sendJson(monitor, { type: "activity", room: source.roomId });
            }
        }
    }

    function broadcastPresence(room) {
        const message = { type: "presence", count: room.members.size };
        for (const member of room.members) {
            sendJson(member, message);
        }
    }

    function sendJson(client, value) {
        if (client.closed) {
            return false;
        }
        const payload = Buffer.from(JSON.stringify(value), "utf8");
        return sendFrame(client, 0x1, payload, null);
    }

    function sendFrame(client, opcode, payload, source) {
        return sendEncodedFrame(client, encodeFrame(opcode, payload), source);
    }

    function sendEncodedFrame(client, frame, source) {
        if (client.closed || client.socket.destroyed || !client.socket.writable) {
            return false;
        }
        let ready;
        try {
            ready = client.socket.write(frame);
        } catch {
            cleanupClient(client);
            return false;
        }
        if (!ready && source && source !== client && !source.closed) {
            client.drainSources.add(source);
            source.waitingForDrain.add(client);
            source.socket.pause();
        }
        return true;
    }

    function releaseDrainTarget(target) {
        const sources = Array.from(target.drainSources);
        target.drainSources.clear();
        for (const source of sources) {
            source.waitingForDrain.delete(target);
            if (
                source.waitingForDrain.size === 0 &&
                !source.closed &&
                !source.socket.destroyed
            ) {
                source.socket.resume();
            }
        }
    }

    function cleanupClient(client) {
        if (client.cleaned) {
            return;
        }
        client.cleaned = true;
        client.closed = true;
        clearTimeout(client.joinTimer);
        client.joinTimer = null;
        clients.delete(client);

        for (const target of client.waitingForDrain) {
            target.drainSources.delete(client);
        }
        client.waitingForDrain.clear();
        releaseDrainTarget(client);

        if (client.mode === "member" && client.roomKey) {
            const room = rooms.get(client.roomKey);
            if (room && room.members.delete(client)) {
                if (room.members.size === 0) {
                    rooms.delete(client.roomKey);
                } else {
                    broadcastPresence(room);
                }
            }
        }
    }

    function closeClient(client, code, reason) {
        if (client.closed) {
            return;
        }
        const reasonBytes = Buffer.from(String(reason ?? "").slice(0, 80), "utf8");
        const payload = Buffer.alloc(2 + reasonBytes.length);
        payload.writeUInt16BE(code, 0);
        reasonBytes.copy(payload, 2);
        try {
            client.socket.write(encodeFrame(0x8, payload));
            client.socket.end();
        } catch {
            client.socket.destroy();
        }
        cleanupClient(client);
    }

    server.closeAll = () => {
        clearInterval(heartbeat);
        for (const client of Array.from(clients)) {
            closeClient(client, 1001, "Server closing");
            client.socket.destroy();
        }
    };
    server.on("close", () => clearInterval(heartbeat));
    return server;
}

function validHex(value, length) {
    return new RegExp(`^[a-f0-9]{${length}}$`).test(value);
}

function keyFor(roomId, proof) {
    return `${roomId}:${proof}`;
}

function encodeFrame(opcode, payload) {
    const length = payload.length;
    let header;
    if (length <= 125) {
        header = Buffer.alloc(2);
        header[1] = length;
    } else if (length <= 65_535) {
        header = Buffer.alloc(4);
        header[1] = 126;
        header.writeUInt16BE(length, 2);
    } else {
        header = Buffer.alloc(10);
        header[1] = 127;
        header.writeBigUInt64BE(BigInt(length), 2);
    }
    header[0] = 0x80 | opcode;
    return Buffer.concat([header, payload]);
}

function rejectUpgrade(socket, status, reason) {
    try {
        socket.end([
            `HTTP/1.1 ${status} ${reason}`,
            "Connection: close",
            "Content-Length: 0",
            "\r\n"
        ].join("\r\n"));
    } catch {
        socket.destroy();
    }
}

if (require.main === module) {
    const port = boundedInteger(process.env.PORT, 8080, 1, 65_535);
    createRelayServer().listen(port, "0.0.0.0");
}

module.exports = { createRelayServer };
