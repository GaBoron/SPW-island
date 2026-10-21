// SPDX-License-Identifier: GPL-3.0-only
// Windows process-loopback API interop; no microphone, system mix or audio file recording.
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace SpwIsland.Audio
{
    [ComVisible(true), ClassInterface(ClassInterfaceType.None)]
    public sealed class Activation : ICompletionHandler, IAgileObject
    {
        internal readonly ManualResetEvent Completed = new ManualResetEvent(false);
        internal object Client;
        internal Exception Error;
        public void ActivateCompleted(IActivationOperation operation)
        {
            try { int hr; operation.GetActivateResult(out hr, out Client); Native.Check(hr); }
            catch (Exception error) { Console.Error.WriteLine(error); Error = error; }
            finally { Completed.Set(); }
        }
    }
    internal static class Program
    {
        private static volatile bool stopping;
        [MTAThread]
        private static int Main(string[] args)
        {
            using (var output = new StreamWriter(Console.OpenStandardOutput()) { AutoFlush = true })
            using (var errors = new StreamWriter(Console.OpenStandardError()) { AutoFlush = true })
            {
                try
                {
                    if (args.Length != 1) throw new ArgumentException("Expected the SPW process ID.");
                    var process = Process.GetProcessById(Int32.Parse(args[0]));
                    new Thread(() => { Console.In.ReadLine(); stopping = true; }) { IsBackground = true }.Start();
                    Capture(process, output);
                    return 0;
                }
                catch (Exception error)
                {
                    errors.WriteLine("WASAPI process loopback: " + error + " (0x" + error.HResult.ToString("X8") + ")");
                    return 1;
                }
            }
        }
        private static void Capture(Process parent, TextWriter output)
        {
            IntPtr blob = Marshal.AllocHGlobal(12);
            IActivationOperation operation = null;
            var activation = new Activation();
            try
            {
                Marshal.WriteInt32(blob, 0, 1); // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
                Marshal.WriteInt32(blob, 4, parent.Id);
                Marshal.WriteInt32(blob, 8, 0); // INCLUDE_TARGET_PROCESS_TREE
                var parameters = new ActivationVariant { Type = 65, Size = 12, Data = blob };
                Guid iid = typeof(IAudioClient).GUID;
                Native.Check(Native.ActivateAudioInterfaceAsync("VAD\\Process_Loopback", ref iid, ref parameters, activation, out operation));
                if (!activation.Completed.WaitOne(10000)) throw new TimeoutException("Audio activation timed out.");
                if (activation.Error != null) throw activation.Error;
                Run((IAudioClient)activation.Client, parent, output);
            }
            finally
            {
                if (activation.Client != null) Marshal.ReleaseComObject(activation.Client);
                if (operation != null) Marshal.ReleaseComObject(operation);
                Marshal.FreeHGlobal(blob);
            }
        }
        private static void Run(IAudioClient client, Process parent, TextWriter output)
        {
            var format = new WaveFormat { Tag = 1, Channels = 2, SampleRate = 44100, BytesPerSecond = 176400, BlockAlign = 4, BitsPerSample = 16 };
            client.Initialize(0, 0x80060000, 0, 0, ref format, IntPtr.Zero);
            Guid iid = typeof(ICaptureClient).GUID;
            object service; client.GetService(ref iid, out service);
            var capture = (ICaptureClient)service;
            var spectrum = new Spectrum();
            using (var ready = new AutoResetEvent(false))
            {
                client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle());
                client.Start(); output.WriteLine("READY");
                var silence = Stopwatch.StartNew();
                try
                {
                    while (!stopping && !parent.HasExited)
                    {
                        ready.WaitOne(100);
                        uint packet; capture.GetNextPacketSize(out packet);
                        while (packet > 0)
                        {
                            IntPtr data; uint frames, flags; ulong device, counter;
                            capture.GetBuffer(out data, out frames, out flags, out device, out counter);
                            try
                            {
                                for (int i = 0; i < frames; i++)
                                {
                                    bool silent = (flags & 2) != 0;
                                    string bands = spectrum.Push(silent ? (short)0 : Marshal.ReadInt16(data, i * 4),
                                        silent ? (short)0 : Marshal.ReadInt16(data, i * 4 + 2));
                                    if (bands != null) { output.WriteLine(bands); silence.Restart(); }
                                }
                            }
                            finally { capture.ReleaseBuffer(frames); }
                            capture.GetNextPacketSize(out packet);
                        }
                        if (silence.ElapsedMilliseconds >= 150) { spectrum.Reset(); output.WriteLine("0,0,0,0"); silence.Restart(); }
                    }
                }
                finally { client.Stop(); Marshal.ReleaseComObject(service); }
            }
        }
    }
}
