# osu! USB keypad

Turns an Android phone into a two-button keypad (default **Z** and **X**) for osu! Stable on Windows 11.
All traffic goes over the USB cable through ADB. No Wi-Fi, Bluetooth, network permission or internet.

> **Status, honestly:** the source is complete, but **nothing here has been compiled or run on a phone or
> on Windows yet.** The environment this was written in could not reach the Android SDK / Maven / NuGet
> servers and has no Windows. What *was* run: the wire protocol, against a fake phone and a stub host
> (see [Testing](#testing)). Expect to possibly fix a compile error or two on first build, and please run
> the checklist below before trusting it in a ranked play.

## Get the binaries

**Easiest (no installs):** push this folder to a GitHub repository, open *Actions -> build -> Run workflow*,
then download the `osu-keypad-apk` and `osu-keypad-host` artifacts. This builds both on GitHub's machines.

**Windows host, locally (no SDK needed):** double-click `host\build.bat`. It uses the C# compiler that is
already part of Windows (.NET Framework 4.x) and produces `host\OsuKeypadHost.exe`.

**Android app, locally:** open `android/` in Android Studio and press Run, or with Gradle 8.9+ and the
Android SDK installed: `cd android && gradle assembleDebug`
-> `android/app/build/outputs/apk/debug/app-debug.apk`.

## Install

1. **Phone: enable USB debugging** (Poco / HyperOS):
   *Settings -> About phone* -> tap **OS version** 7 times -> *Settings -> Additional settings -> Developer
   options* -> turn on **USB debugging**. (On Xiaomi, installing by cable may also need **Install via USB**,
   which asks for a Mi account; otherwise copy the APK to the phone and open it.)
2. **PC: get adb.** Download *SDK Platform-Tools for Windows* from developer.android.com, unzip, and copy
   `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll` next to `OsuKeypadHost.exe`. (The host also finds adb on
   `PATH` or in `%LOCALAPPDATA%\Android\Sdk\platform-tools`.)
3. **Install the APK:** `adb install app-debug.apk` (or open the APK on the phone).
4. Plug in the phone, choose *File transfer* USB mode, and tap **Allow** on the "Allow USB debugging?"
   prompt (tick *Always allow from this computer*).
5. Start `OsuKeypadHost.exe`, then open **osu! keypad** on the phone. The PC window turns green
   (*Connected*) and the phone shows *Connected* with the USB round-trip time.

The host re-detects the phone, re-creates the port forward and reconnects by itself after unplugging,
rebooting the phone or closing/reopening the app. You can start things in any order.

## Using it

- Left/right halves of the screen are the two buttons. Multi-touch works (Z and X together).
- **Change a key:** long-press a button (0.8 s) -> tap a key (applies immediately, back to the pad;
  *Back* cancels). The slider at the bottom of that screen moves the split between the buttons (20-80 %);
  hold your finger still while dragging to switch to **fine** adjustment (10x slower).
- Key choices: A-Z, 0-9, Space, Enter, Esc, Tab, Left Shift, Left Ctrl, arrows, `; , . /`.
- **Deliberate deviation from your spec:** long-press setup is ignored *while the PC host is connected*.
  osu! sliders need a key held for seconds; a long press would pop up the menu and drop the key mid-song.
  To change keys, close the PC host (or unplug), long-press, then reconnect. To allow it any time, set
  `ALLOW_SETUP_WHILE_CONNECTED = true` in `PadView.kt`.

## Architecture

```
 finger -> PadView (touch thread) -> Link writer thread -> abstract unix socket "osukeypad"
        -> adbd -> USB -> adb server (PC) -> 127.0.0.1:27183 -> OsuKeypadHost.exe -> SendInput
```

- Android cannot act as a USB HID keyboard without root/special kernels, so the phone is a *client of
  ADB*: the app listens on a local abstract socket (`LocalServerSocket`, needs no permission) and the PC
  runs `adb forward tcp:27183 localabstract:osukeypad`, then connects to it. Everything is a normal ADB
  tunnel over the USB cable.
- Wire format (both directions) is fixed 2-byte frames, no handshake, no parsing cost:
  `[vk, 1]` key down, `[vk, 0]` key up (vk = Windows virtual-key code), `[0xFF, seq]` ping.
  The phone pings every 100 ms; the host echoes it. That gives a liveness check and the round-trip time
  shown on the phone. `TCP_NODELAY` on the PC, one `write` per event on the phone.
- Key injection is `SendInput` with **scan codes** (`KEYEVENTF_SCANCODE`), so games that read raw
  keyboard state see it like a real keyboard. Down is sent once per press; Windows does not auto-repeat
  injected key-downs and the host ignores duplicate downs anyway.
- Touch: `requestUnbufferedDispatch` (events delivered at the digitizer rate, not vsync-batched), each
  finger is bound to the zone it first landed in, a zone sends down on its first finger and up when its
  last finger lifts. System edge gestures are excluded and the screen stays on.
- **Stuck-key protection:** the host releases every held key when the connection ends for any reason
  (EOF/reset, 0.6 s of silence, exception, window closed, process exit, unhandled exception). The phone
  also sends ups on `onPause` and on touch cancel.
- The Android app has no dependencies and no permissions; the host is one ~400-line C# file.

## Testing

What actually ran (Linux sandbox, Python): `tools/fake_phone.py` driving a stub that re-implements the
host's protocol logic, 6 scenarios, all as expected:

| Scenario | Result |
|---|---|
| 250x Z/X rapid alternation (8 ms taps) | 500 downs / 500 ups, none lost |
| Z+X chords x20 | 40 / 40 |
| Hold Z 3 s, hold X 3 s | one down + one up each |
| Connection reset with Z and X held | both released |
| Phone goes silent with Z held | released after ~0.6 s |
| Ping round trip, loopback, no USB | median ~0.2-0.35 ms |

**Not run (no phone, no Windows, no compilers available): everything else** - the C# host compiling and
injecting keys, the Kotlin app compiling/running, real USB latency, osu! receiving the keys, unplug/replug.
The loopback figure above says nothing about USB; expect adb adds roughly 1-3 ms each way, and the touch
panel's own sampling dominates. The phone shows the real USB round trip once connected.

### Checklist to run on your machine

1. Host without a phone: `OsuKeypadHost.exe --no-adb`, then `python tools\fake_phone.py alt` (also
   `chord`, `hold`, `drop`, `silent`, `latency`) with Notepad focused. Expect: `zxzx...`, no repeats while
   held, nothing typing after `drop`/`silent`.
2. With the phone: tap each button, hold each, press both, then **unplug while holding a button** - no key
   may stay down (open Notepad, or use an online keyboard tester).
3. Replug, confirm it reconnects without touching anything. Close/reopen the app, same.
4. Change a key (host closed), change the split, reopen the app - settings persist.
5. osu!: play a map, check both keys register and sliders hold.

## Known limitations / assumptions to verify

- If the host process is *killed* (Task Manager) while a key is down, Windows keeps that injected key
  logically down until you tap it on a real keyboard. Normal exit, disconnect and crashes are handled.
- If osu! runs **as Administrator**, run the host as Administrator too (Windows blocks injection into
  higher-privilege windows).
- Keys go to the *focused* window, like a real keyboard.
- Assumes Android lets the app bind an abstract socket (normally true). If a ROM blocks it, the fallback is
  a `ServerSocket` on 127.0.0.1 plus the `INTERNET` permission, and `localabstract:` -> `tcp:` in the host.
- Two fingers on the *same* zone behave as one key (held until both lift), like one physical key.
- First run may need the Windows ADB/Xiaomi USB driver if Windows does not install one automatically.
- Debug-signed APK; no Play Store packaging.
