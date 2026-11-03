#!/usr/bin/env python3
"""Fake phone for testing the Windows host without a phone.

1. Start the host in dev mode:   OsuKeypadHost.exe --no-adb
2. Run:                          python fake_phone.py <scenario>

The host connects to 127.0.0.1:27183 (where adb would normally forward to the phone app). This script
plays the phone's role with the exact same wire protocol (see android/.../Link.kt).

Scenarios (focus Notepad or osu! first; the PC types Z/X):
  alt      500 rapid Z/X alternations, 8 ms down time each
  chord    Z+X pressed together, 20 times
  hold     hold Z for 3 s, then X for 3 s (expect ONE character each, no auto-repeat)
  drop     press Z and X, then kill the connection abruptly (USB unplug): keys must be released
  silent   press Z, then go silent but keep the socket open: host must release after ~0.6 s
  latency  idle for 10 s and print ping round-trip stats (socket + host only, no USB)
"""
import os, socket, sys, threading, time, struct

PORT = 27183
PING = 0xFF
Z, X = 0x5A, 0x58


class Phone:
    def __init__(self, conn):
        self.c = conn
        self.lock = threading.Lock()
        self.sent = {}
        self.rtts = []
        self.alive = True
        self.pinging = True
        threading.Thread(target=self._ping, daemon=True).start()
        threading.Thread(target=self._read, daemon=True).start()

    def _send(self, a, b):
        with self.lock:
            self.c.sendall(bytes([a, b]))

    def _ping(self):
        seq = 0
        while self.alive:
            if self.pinging:
                seq = (seq + 1) & 0xFF
                self.sent[seq] = time.perf_counter()
                try:
                    self._send(PING, seq)
                except OSError:
                    return
            time.sleep(0.1)

    def _read(self):
        buf = b""
        while self.alive:
            try:
                d = self.c.recv(2 - len(buf))
            except OSError:
                return
            if not d:
                self.alive = False
                return
            buf += d
            if len(buf) == 2:
                if buf[0] == PING and buf[1] in self.sent:
                    self.rtts.append((time.perf_counter() - self.sent[buf[1]]) * 1000)
                buf = b""

    def key(self, vk, down):
        self._send(vk, 1 if down else 0)


def tap(p, vk, ms=8):
    p.key(vk, True)
    time.sleep(ms / 1000)
    p.key(vk, False)


def main():
    scenario = sys.argv[1] if len(sys.argv) > 1 else "alt"
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))
    srv.listen(1)
    print("waiting for host on 127.0.0.1:%d (run OsuKeypadHost.exe --no-adb)..." % PORT)
    conn, _ = srv.accept()
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    p = Phone(conn)
    print("host connected; running '%s' in 3 s - click into Notepad now" % scenario)
    time.sleep(3)

    if scenario == "alt":
        for i in range(250):
            tap(p, Z); tap(p, X)
    elif scenario == "chord":
        for i in range(20):
            p.key(Z, True); p.key(X, True); time.sleep(0.2)
            p.key(Z, False); p.key(X, False); time.sleep(0.2)
    elif scenario == "hold":
        p.key(Z, True); time.sleep(3); p.key(Z, False)
        p.key(X, True); time.sleep(3); p.key(X, False)
    elif scenario == "drop":
        p.key(Z, True); p.key(X, True); time.sleep(0.5)
        print("killing connection with Z and X held - Notepad must stop receiving them")
        conn.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        sys.stdout.flush()
        os._exit(0)   # hard exit with linger 0: the OS sends a TCP reset, like an unplugged cable
    elif scenario == "silent":
        p.pinging = False
        p.key(Z, True)
        print("Z is down and the phone went silent; host should release Z after ~0.6 s")
        time.sleep(3)
    elif scenario == "latency":
        time.sleep(10)
    else:
        print("unknown scenario"); return

    time.sleep(0.3)
    if p.rtts:
        s = sorted(p.rtts)
        print("pings: n=%d  min %.3f ms  median %.3f ms  p99 %.3f ms  max %.3f ms"
              % (len(s), s[0], s[len(s) // 2], s[min(len(s) - 1, int(len(s) * .99))], s[-1]))
    p.alive = False
    conn.close()


if __name__ == "__main__":
    main()
