"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { createRelayServer } = require("../server");

const ROOM = "a".repeat(64);
const PROOF = "b".repeat(64);
const CLIENT_A = "1".repeat(32);
const CLIENT_B = "2".repeat(32);
const CLIENT_C = "3".repeat(32);
const PAYLOAD = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=";

class Inbox {
    constructor(socket) {
        this.messages = [];
        this.waiters = [];
        this.discardCipher = false;
        socket.addEventListener("message", (event) => {
            let message;
            try {
                message = JSON.parse(String(event.data));
            } catch {
                return;
            }
            const waiterIndex = this.waiters.findIndex((waiter) => waiter.predicate(message));
            if (waiterIndex >= 0) {
                const [waiter] = this.waiters.splice(waiterIndex, 1);
                clearTimeout(waiter.timer);
                waiter.resolve(message);
            } else if (!this.discardCipher || message.type !== "cipher") {
                this.messages.push(message);
            }
        });
    }

    next(predicate, timeoutMs = 2_000) {
        const existingIndex = this.messages.findIndex(predicate);
        if (existingIndex >= 0) {
            const [message] = this.messages.splice(existingIndex, 1);
            return Promise.resolve(message);
        }
        return new Promise((resolve, reject) => {
            const waiter = { predicate, resolve, timer: null };
            waiter.timer = setTimeout(() => {
                const index = this.waiters.indexOf(waiter);
                if (index >= 0) {
                    this.waiters.splice(index, 1);
                }
                reject(new Error("Timed out waiting for message"));
            }, timeoutMs);
            this.waiters.push(waiter);
        });
    }

    async expectNone(predicate, durationMs = 250) {
        assert.equal(this.messages.some(predicate), false);
        await new Promise((resolve) => setTimeout(resolve, durationMs));
        assert.equal(this.messages.some(predicate), false);
    }
}

async function connect(url) {
    const socket = new WebSocket(url);
    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("WebSocket open timeout")), 2_000);
        socket.addEventListener("open", () => {
            clearTimeout(timer);
            resolve();
        }, { once: true });
        socket.addEventListener("error", () => {
            clearTimeout(timer);
            reject(new Error("WebSocket open failed"));
        }, { once: true });
    });
    return { socket, inbox: new Inbox(socket) };
}

async function startRelay(context, options = {}) {
    const relay = createRelayServer({
        maxRoomSize: 3,
        heartbeatMs: 5_000,
        joinTimeoutMs: 1_500,
        ...options
    });
    await new Promise((resolve, reject) => {
        relay.once("error", reject);
        relay.listen(0, "127.0.0.1", resolve);
    });
    const sockets = [];
    context.after(async () => {
        for (const socket of sockets) {
            socket.close();
        }
        relay.closeAll();
        await new Promise((resolve) => relay.close(resolve));
    });
    const address = relay.address();
    return {
        sockets,
        baseUrl: `ws://127.0.0.1:${address.port}`
    };
}

function memberJoin(client, media = 1) {
    return JSON.stringify({
        type: "join",
        room: ROOM,
        proof: PROOF,
        client,
        media
    });
}

test("relay iletir, geçmişi oynatmaz ve oda sınırını uygular", async (context) => {
    const { sockets, baseUrl } = await startRelay(context);
    const health = await fetch(`${baseUrl.replace("ws://", "http://")}/health`);
    assert.equal(health.status, 200);
    assert.equal(health.headers.get("cache-control"), "no-store");
    assert.deepEqual(await health.json(), {
        status: "ok",
        protocol: 4,
        media: true,
        notifications: true,
        receipts: true,
        replies: true,
        viewOnce: true
    });

    const first = await connect(`${baseUrl}/chat`);
    const second = await connect(`${baseUrl}/chat`);
    sockets.push(first.socket, second.socket);
    first.socket.send(memberJoin(CLIENT_A));
    second.socket.send(memberJoin(CLIENT_B));
    const firstJoined = await first.inbox.next((message) => message.type === "joined");
    const secondJoined = await second.inbox.next((message) => message.type === "joined");
    assert.deepEqual(
        {
            protocol: firstJoined.protocol,
            media: firstJoined.media,
            notifications: firstJoined.notifications,
            receipts: firstJoined.receipts
        },
        { protocol: 4, media: 1, notifications: 1, receipts: 1 }
    );
    assert.deepEqual(
        {
            protocol: secondJoined.protocol,
            media: secondJoined.media,
            notifications: secondJoined.notifications,
            receipts: secondJoined.receipts
        },
        { protocol: 4, media: 1, notifications: 1, receipts: 1 }
    );
    await first.inbox.next((message) => message.type === "presence" && message.count === 2);

    first.socket.send(JSON.stringify({ type: "cipher", payload: PAYLOAD }));
    const delivered = await second.inbox.next((message) => message.type === "cipher");
    assert.equal(delivered.payload, PAYLOAD);
    assert.equal(delivered.kind, "text");

    const third = await connect(`${baseUrl}/chat`);
    sockets.push(third.socket);
    third.socket.send(memberJoin(CLIENT_C, 0));
    await third.inbox.next((message) => message.type === "joined");
    await third.inbox.expectNone((message) => message.type === "cipher");

    first.socket.send(JSON.stringify({
        type: "cipher",
        kind: "media",
        stage: "start",
        payload: PAYLOAD
    }));
    const deliveredMedia = await second.inbox.next(
        (message) => message.type === "cipher" && message.kind === "media"
    );
    assert.equal(deliveredMedia.payload, PAYLOAD);
    await third.inbox.expectNone(
        (message) => message.type === "cipher" && message.kind === "media"
    );

    const fourth = await connect(`${baseUrl}/chat`);
    sockets.push(fourth.socket);
    fourth.socket.send(memberJoin("4".repeat(32)));
    const refusal = await fourth.inbox.next((message) => message.type === "error");
    assert.equal(refusal.code, "room_full");
});

test("v1.5 kabul ve şifreli teslim makbuzlarını kaynak cihaza iletir", async (context) => {
    const { sockets, baseUrl } = await startRelay(context, { maxRoomSize: 2 });
    const first = await connect(`${baseUrl}/chat`);
    const second = await connect(`${baseUrl}/chat`);
    sockets.push(first.socket, second.socket);
    first.socket.send(memberJoin(CLIENT_A));
    second.socket.send(memberJoin(CLIENT_B));
    await first.inbox.next((message) => message.type === "joined");
    await second.inbox.next((message) => message.type === "joined");
    await first.inbox.next((message) => message.type === "presence" && message.count === 2);

    const messageId = "d".repeat(32);
    first.socket.send(JSON.stringify({
        type: "cipher",
        kind: "text",
        id: messageId,
        payload: PAYLOAD
    }));
    const accepted = await first.inbox.next(
        (message) => message.type === "accepted" && message.id === messageId
    );
    assert.equal(accepted.recipients, 1);
    const delivered = await second.inbox.next(
        (message) => message.type === "cipher" && message.payload === PAYLOAD
    );
    assert.equal(delivered.kind, "text");
    await first.inbox.expectNone(
        (message) => message.type === "cipher" && message.payload === PAYLOAD
    );

    const receiptPayload = "R".repeat(24);
    second.socket.send(JSON.stringify({
        type: "cipher",
        kind: "receipt",
        payload: receiptPayload
    }));
    const receipt = await first.inbox.next(
        (message) => message.type === "cipher" && message.payload === receiptPayload
    );
    assert.equal(receipt.kind, "receipt");
    assert.equal(first.socket.readyState, WebSocket.OPEN);
    assert.equal(second.socket.readyState, WebSocket.OPEN);
});

test("yüksek boyutlu medya ve yoğun mesaj bağlantıyı koparmaz", async (context) => {
    const { sockets, baseUrl } = await startRelay(context, { maxRoomSize: 2 });
    const first = await connect(`${baseUrl}/chat`);
    const second = await connect(`${baseUrl}/chat`);
    sockets.push(first.socket, second.socket);
    first.socket.send(memberJoin(CLIENT_A));
    second.socket.send(memberJoin(CLIENT_B));
    await first.inbox.next((message) => message.type === "joined");
    await second.inbox.next((message) => message.type === "joined");
    second.inbox.discardCipher = true;

    const duringPayload = "D".repeat(24);
    const endPayload = "E".repeat(24);
    const finalTextPayload = "F".repeat(24);
    const during = second.inbox.next(
        (message) => message.type === "cipher" && message.payload === duringPayload,
        30_000
    );
    const completed = second.inbox.next(
        (message) => message.type === "cipher" && message.payload === endPayload,
        30_000
    );
    const finalText = second.inbox.next(
        (message) => message.type === "cipher" && message.payload === finalTextPayload,
        30_000
    );

    first.socket.send(JSON.stringify({
        type: "cipher",
        kind: "media",
        stage: "start",
        payload: "S".repeat(24)
    }));
    const largeChunk = "A".repeat(30_000);
    for (let index = 0; index < 1_700; index += 1) {
        first.socket.send(JSON.stringify({
            type: "cipher",
            kind: "media",
            stage: "chunk",
            payload: largeChunk
        }));
        if (index === 100) {
            first.socket.send(JSON.stringify({
                type: "cipher",
                kind: "text",
                payload: duringPayload
            }));
        }
    }
    for (let index = 0; index < 900; index += 1) {
        first.socket.send(JSON.stringify({
            type: "cipher",
            kind: "media",
            stage: "chunk",
            payload: "C".repeat(24)
        }));
    }
    first.socket.send(JSON.stringify({
        type: "cipher",
        kind: "media",
        stage: "end",
        payload: endPayload
    }));
    for (let index = 0; index < 100; index += 1) {
        first.socket.send(JSON.stringify({
            type: "cipher",
            kind: "text",
            payload: "T".repeat(24)
        }));
    }
    first.socket.send(JSON.stringify({
        type: "cipher",
        kind: "text",
        payload: finalTextPayload
    }));

    await during;
    await completed;
    const deliveredText = await finalText;
    assert.equal(deliveredText.kind, "text");
    assert.equal(first.socket.readyState, WebSocket.OPEN);
    assert.equal(second.socket.readyState, WebSocket.OPEN);
});

test("aynı cihazın yeni oturumu eski bağlantıyı değiştirir", async (context) => {
    const { sockets, baseUrl } = await startRelay(context);
    const first = await connect(`${baseUrl}/chat`);
    const second = await connect(`${baseUrl}/chat`);
    sockets.push(first.socket, second.socket);
    first.socket.send(memberJoin(CLIENT_A));
    second.socket.send(memberJoin(CLIENT_B));
    await first.inbox.next((message) => message.type === "joined");
    await second.inbox.next((message) => message.type === "joined");
    await second.inbox.next((message) => message.type === "presence" && message.count === 2);

    const replaced = new Promise((resolve) => {
        first.socket.addEventListener("close", (event) => resolve(event.code), { once: true });
    });
    const replacement = await connect(`${baseUrl}/chat`);
    sockets.push(replacement.socket);
    replacement.socket.send(memberJoin(CLIENT_A));
    await replacement.inbox.next((message) => message.type === "joined");
    await replacement.inbox.next(
        (message) => message.type === "presence" && message.count === 2
    );
    assert.equal(await replaced, 4000);
    await second.inbox.expectNone(
        (message) => message.type === "presence" && message.count === 3
    );
});

test("bildirim izleyicisi kişi sayısına girmez ve yalnız etkinlik alır", async (context) => {
    const { sockets, baseUrl } = await startRelay(context);
    const monitor = await connect(`${baseUrl}/chat`);
    sockets.push(monitor.socket);
    monitor.socket.send(JSON.stringify({
        type: "monitor",
        client: CLIENT_A,
        rooms: [{ room: ROOM, proof: PROOF }]
    }));
    const monitorJoined = await monitor.inbox.next((message) => message.type === "joined");
    assert.deepEqual(
        {
            mode: monitorJoined.mode,
            protocol: monitorJoined.protocol,
            notifications: monitorJoined.notifications
        },
        { mode: "monitor", protocol: 4, notifications: 1 }
    );

    const first = await connect(`${baseUrl}/chat`);
    const second = await connect(`${baseUrl}/chat`);
    sockets.push(first.socket, second.socket);
    first.socket.send(memberJoin(CLIENT_A));
    second.socket.send(memberJoin(CLIENT_B));
    await first.inbox.next((message) => message.type === "joined");
    await second.inbox.next((message) => message.type === "joined");
    await first.inbox.next((message) => message.type === "presence" && message.count === 2);
    await monitor.inbox.expectNone((message) => message.type === "presence");

    second.socket.send(JSON.stringify({
        type: "cipher",
        kind: "text",
        payload: PAYLOAD
    }));
    const activity = await monitor.inbox.next((message) => message.type === "activity");
    assert.equal(activity.room, ROOM);
    await monitor.inbox.expectNone((message) => message.type === "cipher");

    second.socket.send(JSON.stringify({
        type: "cipher",
        kind: "receipt",
        payload: "R".repeat(24)
    }));
    await first.inbox.next(
        (message) => message.type === "cipher" && message.kind === "receipt"
    );
    await monitor.inbox.expectNone((message) => message.type === "activity");

    first.socket.send(JSON.stringify({
        type: "cipher",
        kind: "text",
        payload: "Z".repeat(24)
    }));
    await monitor.inbox.expectNone((message) => message.type === "activity");
});
