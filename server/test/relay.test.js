"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { createRelayServer } = require("../server");

class Inbox {
    constructor(socket) {
        this.socket = socket;
        this.messages = [];
        this.waiters = [];
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
            } else {
                this.messages.push(message);
            }
        });
    }

    next(predicate, timeoutMs = 1_500) {
        const existingIndex = this.messages.findIndex(predicate);
        if (existingIndex >= 0) {
            const [message] = this.messages.splice(existingIndex, 1);
            return Promise.resolve(message);
        }
        return new Promise((resolve, reject) => {
            const waiter = { predicate, resolve, reject, timer: null };
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

    async expectNone(predicate, durationMs = 200) {
        assert.equal(this.messages.some(predicate), false);
        await new Promise((resolve) => setTimeout(resolve, durationMs));
        assert.equal(this.messages.some(predicate), false);
    }
}

async function connect(url) {
    const socket = new WebSocket(url);
    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("WebSocket open timeout")), 1_500);
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

test("relay iletir, geçmişi tekrar oynatmaz ve oda sınırını uygular", async (context) => {
    const relay = createRelayServer({
        maxRoomSize: 3,
        heartbeatMs: 5_000,
        joinTimeoutMs: 1_500
    });
    await new Promise((resolve, reject) => {
        relay.once("error", reject);
        relay.listen(0, "127.0.0.1", resolve);
    });

    const address = relay.address();
    const baseUrl = `ws://127.0.0.1:${address.port}`;
    const sockets = [];
    context.after(async () => {
        for (const socket of sockets) {
            socket.close();
        }
        relay.closeAll();
        await new Promise((resolve) => relay.close(resolve));
    });

    const health = await fetch(`${baseUrl.replace("ws://", "http://")}/health`);
    assert.equal(health.status, 200);
    assert.equal(health.headers.get("cache-control"), "no-store");

    const room = "a".repeat(64);
    const proof = "b".repeat(64);
    const join = JSON.stringify({ type: "join", room, proof });

    const first = await connect(`${baseUrl}/chat`);
    const second = await connect(`${baseUrl}/chat`);
    sockets.push(first.socket, second.socket);
    first.socket.send(join);
    second.socket.send(join);
    await first.inbox.next((message) => message.type === "joined");
    await second.inbox.next((message) => message.type === "joined");
    await first.inbox.next((message) => message.type === "presence" && message.count === 2);

    const payload = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=";
    first.socket.send(JSON.stringify({ type: "cipher", payload }));
    const delivered = await second.inbox.next((message) => message.type === "cipher");
    assert.equal(delivered.payload, payload);

    const third = await connect(`${baseUrl}/chat`);
    sockets.push(third.socket);
    third.socket.send(join);
    await third.inbox.next((message) => message.type === "joined");
    await third.inbox.expectNone((message) => message.type === "cipher");

    const fourth = await connect(`${baseUrl}/chat`);
    sockets.push(fourth.socket);
    fourth.socket.send(join);
    const refusal = await fourth.inbox.next((message) => message.type === "error");
    assert.equal(refusal.code, "room_full");
});
