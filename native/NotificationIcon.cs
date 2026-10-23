// SPDX-License-Identifier: GPL-3.0-only
using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace SpwIsland.Tray
{
    // Owns the Shell registration; the executable's path and this GUID must stay stable.
    internal sealed class NotificationIcon : NativeWindow, IDisposable
    {
        private const int CallbackMessage = 0x8001;
        private const uint Add = 0, Delete = 2, SetVersion = 4;
        private const uint IconId = 1;
        private const uint GuidFlag = 0x20;
        private static readonly Guid IconGuid = new Guid("dbc76514-1111-48b5-9113-6bf71e8776f1");
        private readonly int taskbarCreated;
        private readonly Icon icon;
        private readonly Action toggle;
        private readonly Action<Point> menu;
        private bool registered;
        private bool disposed;

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct IconData
        {
            public uint Size;
            public IntPtr Window;
            public uint Id;
            public uint Flags;
            public uint Callback;
            public IntPtr Icon;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Tip;
            public uint State;
            public uint StateMask;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string Info;
            public uint Version;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string InfoTitle;
            public uint InfoFlags;
            public Guid Guid;
            public IntPtr BalloonIcon;
        }

        [DllImport("shell32.dll", EntryPoint = "Shell_NotifyIconW", CharSet = CharSet.Unicode, ExactSpelling = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool ShellNotifyIcon(uint message, ref IconData data);

        [DllImport("user32.dll", EntryPoint = "RegisterWindowMessageW", CharSet = CharSet.Unicode, ExactSpelling = true)]
        private static extern uint RegisterWindowMessage(string message);

        public NotificationIcon(Icon icon, Action toggle, Action<Point> menu)
        {
            this.icon = icon;
            this.toggle = toggle;
            this.menu = menu;
            taskbarCreated = unchecked((int)RegisterWindowMessage("TaskbarCreated"));
            if (taskbarCreated == 0) throw new InvalidOperationException("无法监听 Windows 托盘恢复事件");
            // TaskbarCreated is broadcast to top-level windows, not message-only windows.
            CreateHandle(new CreateParams { Caption = "SPW Island notification icon", ExStyle = 0x80 });
            try { RegisterIcon(); }
            catch { DestroyHandle(); throw; }
        }

        private IconData Data()
        {
            return new IconData
            {
                Size = (uint)Marshal.SizeOf(typeof(IconData)),
                Window = Handle,
                Id = IconId,
                Flags = 0x01 | 0x02 | 0x04 | GuidFlag | 0x80, // MESSAGE, ICON, TIP, GUID, SHOWTIP
                Callback = CallbackMessage,
                Icon = icon.Handle,
                Tip = "灵动词岛 for SPW",
                Info = String.Empty,
                InfoTitle = String.Empty,
                Guid = IconGuid,
                Version = 4
            };
        }

        private void RegisterIcon()
        {
            IconData data = Data();
            if (!ShellNotifyIcon(Add, ref data)) throw new InvalidOperationException("Windows 托盘图标注册失败");
            registered = true;
            if (!ShellNotifyIcon(SetVersion, ref data))
            {
                RemoveIcon();
                throw new InvalidOperationException("Windows 托盘图标版本设置失败");
            }
        }

        private void RemoveIcon()
        {
            if (!registered) return;
            IconData data = Data();
            if (!ShellNotifyIcon(Delete, ref data)) Console.Error.WriteLine("Windows 托盘图标注销失败");
            registered = false;
        }

        protected override void WndProc(ref Message message)
        {
            if (!disposed && message.Msg == taskbarCreated)
            {
                registered = false;
                try { RegisterIcon(); }
                catch (Exception error) { Console.Error.WriteLine(error); Application.ExitThread(); }
                return;
            }
            if (!disposed && message.Msg == CallbackMessage)
            {
                int action = (int)(message.LParam.ToInt64() & 0xffff);
                if (action == 0x0203 || action == 0x0401) toggle(); // double-click or keyboard select
                else if (action == 0x007b) // VERSION_4 mouse/keyboard context menu
                {
                    long position = message.WParam.ToInt64();
                    var point = new Point(unchecked((short)(position & 0xffff)),
                        unchecked((short)((position >> 16) & 0xffff)));
                    if (point.X == -1 && point.Y == -1) point = Cursor.Position;
                    menu(point);
                }
                message.Result = IntPtr.Zero;
                return;
            }
            base.WndProc(ref message);
        }

        public void Dispose()
        {
            if (disposed) return;
            disposed = true;
            try { RemoveIcon(); }
            finally { DestroyHandle(); }
        }
    }
}
