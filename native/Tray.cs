// SPDX-License-Identifier: GPL-3.0-only
using System;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace SpwIsland.Tray
{
    // A separate executable gives Windows a tray identity distinct from SPW's process.
    // The menu stays in the plugin; this process only forwards icon gestures.
    internal static class Program
    {
        [DllImport("user32.dll")]
        private static extern bool DestroyIcon(IntPtr icon);

        [DllImport("user32.dll")]
        private static extern bool AllowSetForegroundWindow(uint processId);

        [STAThread]
        private static int Main(string[] args)
        {
            if (args.Length != 2) return 2;
            uint parentId;
            if (!UInt32.TryParse(args[1], out parentId)) return 2;
            using (var output = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true })
            using (var commands = new StreamReader(Console.OpenStandardInput(), Encoding.UTF8))
            {
                try
                {
                    Application.EnableVisualStyles();
                    using (var image = new MemoryStream(Convert.FromBase64String(args[0])))
                    using (var bitmap = new Bitmap(image))
                    {
                        IntPtr handle = bitmap.GetHicon();
                        try
                        {
                            using (var icon = Icon.FromHandle(handle))
                            using (var tray = new NotifyIcon())
                            {
                                tray.Icon = icon;
                                tray.Text = "灵动词岛 for SPW";
                                tray.MouseDoubleClick += (sender, eventArgs) =>
                                {
                                    if (eventArgs.Button == MouseButtons.Left) output.WriteLine("TOGGLE");
                                };
                                tray.MouseUp += (sender, eventArgs) =>
                                {
                                    if (eventArgs.Button != MouseButtons.Right) return;
                                    Point cursor = Cursor.Position;
                                    AllowSetForegroundWindow(parentId);
                                    output.WriteLine("MENU\t{0}\t{1}", cursor.X, cursor.Y);
                                };
                                tray.Visible = true;
                                output.WriteLine("READY");
                                var input = new Thread(() =>
                                {
                                    try
                                    {
                                        string line;
                                        while ((line = commands.ReadLine()) != null && line != "STOP") { }
                                    }
                                    catch (Exception) { }
                                    Application.Exit();
                                });
                                input.IsBackground = true;
                                input.Start();
                                Application.Run();
                                tray.Visible = false;
                            }
                        }
                        finally { DestroyIcon(handle); }
                    }
                    return 0;
                }
                catch (Exception error)
                {
                    output.WriteLine("ERROR\t" + error.Message.Replace('\n', ' ').Replace('\r', ' '));
                    return 1;
                }
            }
        }
    }
}
