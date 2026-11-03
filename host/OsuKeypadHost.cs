// osu! USB keypad - Windows host.
//
// Build with the C# compiler that ships with Windows (no SDK needed), see build.bat.
// Written in C# 5 on purpose: that is what the built-in .NET Framework csc.exe understands.
//
// Flow: find adb.exe -> wait for a phone in "device" state -> `adb forward` a local TCP port to the
// phone app's abstract unix socket -> connect -> read 2-byte frames [vk, state] -> SendInput.
// Every frame with vk == 0xFF is a ping from the phone and is echoed back (latency + liveness).
// Any loss of the connection (EOF, timeout, error, exit) releases every key that is held.

using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

static class Kb
{
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT
    {
        public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT
    {
        public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    struct INPUTUNION
    {
        [FieldOffset(0)] public MOUSEINPUT mi;   // present so the struct has the real size of the union
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct INPUT
    {
        public uint type;
        public INPUTUNION u;
    }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    static extern uint MapVirtualKey(uint uCode, uint uMapType);

    const uint KEYEVENTF_EXTENDEDKEY = 0x1;
    const uint KEYEVENTF_KEYUP = 0x2;
    const uint KEYEVENTF_SCANCODE = 0x8;

    static readonly object L = new object();
    static readonly bool[] downKeys = new bool[256];
    static readonly int InputSize = Marshal.SizeOf(typeof(INPUT));

    static bool IsExtended(byte vk)
    {
        // arrows, ins/del/home/end/pgup/pgdn
        return (vk >= 0x21 && vk <= 0x28) || vk == 0x2D || vk == 0x2E;
    }

    // Scan-code injection: games (osu! included) that read raw keyboard state see these like a real keyboard.
    static void Send(byte vk, bool down)
    {
        INPUT[] a = new INPUT[1];
        a[0].type = 1; // INPUT_KEYBOARD
        uint scan = MapVirtualKey(vk, 0); // MAPVK_VK_TO_VSC
        uint flags = 0;
        if (scan != 0)
        {
            a[0].u.ki.wVk = 0;
            a[0].u.ki.wScan = (ushort)scan;
            flags |= KEYEVENTF_SCANCODE;
        }
        else
        {
            a[0].u.ki.wVk = vk;
        }
        if (IsExtended(vk)) flags |= KEYEVENTF_EXTENDEDKEY;
        if (!down) flags |= KEYEVENTF_KEYUP;
        a[0].u.ki.dwFlags = flags;
        SendInput(1, a, InputSize);
    }

    // Down is sent once; windows does not auto-repeat synthetic key-downs, and we ignore duplicates anyway.
    public static void Down(byte vk)
    {
        lock (L)
        {
            if (downKeys[vk]) return;
            downKeys[vk] = true;
            Send(vk, true);
        }
    }

    public static void Up(byte vk)
    {
        lock (L)
        {
            if (!downKeys[vk]) return;
            downKeys[vk] = false;
            Send(vk, false);
        }
    }

    public static void ReleaseAll()
    {
        lock (L)
        {
            for (int i = 0; i < 256; i++)
            {
                if (downKeys[i])
                {
                    downKeys[i] = false;
                    Send((byte)i, false);
                }
            }
        }
    }
}

static class Link
{
    public const int Port = 27183;
    public const string AbstractName = "osukeypad";   // must match the Android app

    public static volatile bool Stop;
    public static volatile bool NoAdb;           // dev mode: connect straight to localhost:Port (see tools/fake_phone.py)
    public static volatile bool Connected;
    public static volatile string Status = "Starting...";
    public static volatile string Detail = "";

    static void Set(bool connected, string status, string detail)
    {
        Connected = connected;
        Status = status;
        Detail = detail;
    }

    static string FindAdb()
    {
        string p = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "adb.exe");
        if (File.Exists(p)) return p;
        string path = Environment.GetEnvironmentVariable("PATH");
        if (path != null)
        {
            foreach (string d in path.Split(';'))
            {
                try
                {
                    string c = Path.Combine(d.Trim().Trim('"'), "adb.exe");
                    if (File.Exists(c)) return c;
                }
                catch (Exception) { }
            }
        }
        string la = Environment.GetEnvironmentVariable("LOCALAPPDATA");
        if (la != null)
        {
            p = Path.Combine(la, @"Android\Sdk\platform-tools\adb.exe");
            if (File.Exists(p)) return p;
        }
        return null;
    }

    // Runs adb. capture=false leaves stdout alone: `adb start-server` leaves a daemon that inherits
    // redirected pipes and would make ReadToEnd hang.
    static string Exec(string exe, string args, int timeoutMs, bool capture)
    {
        try
        {
            ProcessStartInfo psi = new ProcessStartInfo(exe, args);
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardOutput = capture;
            using (Process p = Process.Start(psi))
            {
                string o = "";
                if (capture) o = p.StandardOutput.ReadToEnd();
                if (!p.WaitForExit(timeoutMs))
                {
                    try { p.Kill(); } catch (Exception) { }
                    return null;
                }
                return o;
            }
        }
        catch (Exception)
        {
            return null;
        }
    }

    // Returns "device" when a usable phone is attached, otherwise the adb state ("unauthorized", "offline") or null.
    static string QueryDevice(string adb, out string serial)
    {
        serial = null;
        Exec(adb, "start-server", 8000, false);
        string o = Exec(adb, "devices", 4000, true);
        if (o == null) return null;
        string other = null;
        foreach (string line in o.Split('\n'))
        {
            string l = line.Trim();
            if (l.Length == 0 || l.StartsWith("List of") || l.StartsWith("*")) continue;
            string[] p = l.Split(new char[] { '\t', ' ' }, StringSplitOptions.RemoveEmptyEntries);
            if (p.Length < 2) continue;
            if (p[1] == "device") { serial = p[0]; return "device"; }
            if (other == null) other = p[1];
        }
        return other;
    }

    static bool ReadFull(NetworkStream ns, byte[] b)
    {
        int o = 0;
        while (o < b.Length)
        {
            int n = ns.Read(b, o, b.Length - o);
            if (n <= 0) return false;
            o += n;
        }
        return true;
    }

    // One connection to the phone. Returns true if the phone actually talked to us.
    static bool Session()
    {
        TcpClient c = null;
        bool got = false;
        try
        {
            c = new TcpClient();
            c.NoDelay = true;
            c.ReceiveTimeout = 600;   // the phone pings every 100 ms; silence for 600 ms means dead
            c.SendTimeout = 600;
            c.Connect(IPAddress.Loopback, Port);
            NetworkStream ns = c.GetStream();
            byte[] b = new byte[2];
            while (!Stop)
            {
                if (!ReadFull(ns, b)) break;
                if (!got)
                {
                    got = true;
                    Set(true, "Connected", "Phone is sending input");
                }
                if (b[0] == 0xFF)
                {
                    ns.Write(b, 0, 2);          // echo ping
                }
                else if (b[0] != 0)
                {
                    if (b[1] == 1) Kb.Down(b[0]); else Kb.Up(b[0]);
                }
            }
        }
        catch (Exception) { }
        finally
        {
            Kb.ReleaseAll();                    // never leave a key stuck, whatever happened
            if (c != null) { try { c.Close(); } catch (Exception) { } }
        }
        if (got) Set(false, "Disconnected", "Connection lost - keys released");
        else Set(false, "Disconnected", NoAdb ? "Waiting for fake phone" : "Phone found - open the osu! keypad app");
        return got;
    }

    public static void Loop()
    {
        Thread.CurrentThread.Priority = ThreadPriority.Highest;
        string adb = null;
        while (!Stop)
        {
            try
            {
                string serial = null;
                if (!NoAdb)
                {
                    if (adb == null || !File.Exists(adb)) adb = FindAdb();
                    if (adb == null)
                    {
                        Set(false, "Disconnected", "adb.exe not found - put it next to this program (see README)");
                        Thread.Sleep(1000);
                        continue;
                    }
                    string state = QueryDevice(adb, out serial);
                    if (state != "device")
                    {
                        string d;
                        if (state == null) d = "Plug in the phone (USB debugging on)";
                        else if (state == "unauthorized") d = "Tap Allow on the phone's USB debugging prompt";
                        else d = "Phone state: " + state;
                        Set(false, "Disconnected", d);
                        Thread.Sleep(1000);
                        continue;
                    }
                    string fwd = Exec(adb, "-s " + serial + " forward tcp:" + Port + " localabstract:" + AbstractName, 4000, false);
                    if (fwd == null)
                    {
                        Set(false, "Disconnected", "adb forward failed");
                        Thread.Sleep(1000);
                        continue;
                    }
                }

                // Reconnect quickly while the device stays attached (app may just not be open yet).
                int fails = 0;
                while (!Stop && fails < 10)
                {
                    if (Session()) break;   // had a live session: re-check the device from scratch
                    fails++;
                    Thread.Sleep(300);
                }
            }
            catch (Exception)
            {
                Kb.ReleaseAll();
                Thread.Sleep(500);
            }
        }
        Kb.ReleaseAll();
    }

    public static void Cleanup()
    {
        Stop = true;
        Kb.ReleaseAll();
    }
}

class MainForm : Form
{
    readonly Label status = new Label();
    readonly Label detail = new Label();
    readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();

    public MainForm()
    {
        Text = "osu! USB keypad";
        ClientSize = new Size(380, 110);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        BackColor = Color.FromArgb(20, 20, 22);
        ForeColor = Color.White;

        status.Font = new Font("Segoe UI", 20f, FontStyle.Bold);
        status.AutoSize = false;
        status.SetBounds(12, 8, 356, 44);
        detail.Font = new Font("Segoe UI", 9.5f);
        detail.ForeColor = Color.Silver;
        detail.AutoSize = false;
        detail.SetBounds(14, 56, 356, 44);
        Controls.Add(status);
        Controls.Add(detail);

        timer.Interval = 250;
        timer.Tick += delegate { Refresh2(); };
        timer.Start();
        Refresh2();
    }

    void Refresh2()
    {
        status.Text = Link.Status;
        status.ForeColor = Link.Connected ? Color.LimeGreen : Color.OrangeRed;
        detail.Text = Link.Detail + "\r\nKeys go to the focused window. Keep this program running while you play.";
    }
}

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        bool created;
        Mutex single = new Mutex(true, "osu-usb-keypad-host", out created);
        if (!created) return;

        foreach (string a in args)
        {
            if (a == "--no-adb") Link.NoAdb = true;
        }

        AppDomain.CurrentDomain.ProcessExit += delegate { Link.Cleanup(); };
        AppDomain.CurrentDomain.UnhandledException += delegate { Link.Cleanup(); };

        Thread t = new Thread(Link.Loop);
        t.IsBackground = true;
        t.Name = "link";
        t.Start();

        Application.EnableVisualStyles();
        Application.Run(new MainForm());

        Link.Cleanup();
        GC.KeepAlive(single);
    }
}
