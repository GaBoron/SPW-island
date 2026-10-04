# SPDX-License-Identifier: GPL-3.0-only
"""Wayland pointer sources: ephemeral KWin script or read-only GNOME companion extension."""
import ctypes as C
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import threading
import time

P, S, I = C.c_void_p, C.c_char_p, C.c_int
INTERFACE = 'io.github.gaboron.SpwIsland.Pointer'
OBJECT = '/io/github/gaboron/SpwIsland/Pointer'


def script_source(destination, name):
    # Strings keep the D-Bus signature stable across QJSEngine numeric conversions. Sample while stationary too,
    # so the UI can expire coordinates after a stalled helper or compositor restart.
    return '''var destination = %s, scriptName = %s;
var timer = new QTimer();
var inFlight = false;
function publish() {
    if (inFlight) return;
    inFlight = true;
    var p = workspace.cursorPos;
    callDBus(destination, "%s", "%s", "Update", String(p.x), String(p.y), function() { inFlight = false; });
}
timer.interval = 50;
timer.timeout.connect(publish);
timer.start();
publish();
// Reap the script even if the helper was killed and could not unload it.
var cleanup = new QTimer();
cleanup.interval = 1000;
cleanup.timeout.connect(function() {
    callDBus("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameHasOwner",
        destination, function(alive) {
            if (!alive) {
                timer.stop(); cleanup.stop();
                callDBus("org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting", "unloadScript", scriptName);
            }
        });
});
cleanup.start();
''' % (json.dumps(destination), json.dumps(name), OBJECT, INTERFACE)


class Bus:
    def __init__(self):
        self.gio = C.CDLL('libgio-2.0.so.0')
        self.glib = C.CDLL('libglib-2.0.so.0')
        self.callbacks = []
        def bind(lib, name, result, *arguments):
            fn = getattr(lib, name)
            fn.restype, fn.argtypes = result, list(arguments)
            return fn
        self.bus_get = bind(self.gio, 'g_bus_get_sync', P, I, P, P)
        self.unique = bind(self.gio, 'g_dbus_connection_get_unique_name', S, P)
        self.call_sync = bind(self.gio, 'g_dbus_connection_call_sync', P, P, S, S, S, S, P, P, I, I, P, P)
        self.node_new = bind(self.gio, 'g_dbus_node_info_new_for_xml', P, S, P)
        self.node_lookup = bind(self.gio, 'g_dbus_node_info_lookup_interface', P, P, S)
        self.register = bind(self.gio, 'g_dbus_connection_register_object', C.c_uint, P, S, P, P, P, P, P)
        self.unregister = bind(self.gio, 'g_dbus_connection_unregister_object', I, P, C.c_uint)
        self.reply = bind(self.gio, 'g_dbus_method_invocation_return_value', None, P, P)
        self.error_reply = bind(self.gio, 'g_dbus_method_invocation_return_dbus_error', None, P, S, S)
        self.string = bind(self.glib, 'g_variant_new_string', P, S)
        self.tuple = bind(self.glib, 'g_variant_new_tuple', P, P, C.c_size_t)
        self.child = bind(self.glib, 'g_variant_get_child_value', P, P, C.c_size_t)
        self.int32 = bind(self.glib, 'g_variant_get_int32', I, P)
        self.double = bind(self.glib, 'g_variant_get_double', C.c_double, P)
        self.type_new = bind(self.glib, 'g_variant_type_new', P, S)
        self.type_free = bind(self.glib, 'g_variant_type_free', None, P)
        self.get_string = bind(self.glib, 'g_variant_get_string', S, P, P)
        self.unref = bind(self.glib, 'g_variant_unref', None, P)
        self.loop_new = bind(self.glib, 'g_main_loop_new', P, P, I)
        self.loop_run = bind(self.glib, 'g_main_loop_run', None, P)
        self.loop_quit = bind(self.glib, 'g_main_loop_quit', None, P)
        self.loop_unref = bind(self.glib, 'g_main_loop_unref', None, P)
        self.timeout = bind(self.glib, 'g_timeout_add', C.c_uint, C.c_uint, P, P)
        self.remove_source = bind(self.glib, 'g_source_remove', I, C.c_uint)
        self.connection = self.bus_get(2, None, None)  # G_BUS_TYPE_SESSION
        if not self.connection:
            raise RuntimeError('会话 D-Bus 不可用')

    def call(self, service, path, interface, method, strings=(), signature=None):
        children = (P * len(strings))(*(self.string(s.encode()) for s in strings))
        params = self.tuple(children, len(strings))
        reply_type = self.type_new(signature.encode()) if signature else None
        try:
            result = self.call_sync(self.connection, service.encode(), path.encode(), interface.encode(),
                                    method.encode(), params, reply_type, 0, 750, None, None)
        finally:
            if reply_type:
                self.type_free(reply_type)
        if not result:
            raise RuntimeError(f'D-Bus {method} 失败')
        return result

    def first(self, variant, reader):
        child = self.child(variant, 0)
        try:
            return reader(child)
        finally:
            self.unref(child)
            self.unref(variant)


def run(parent):
    bus = Bus()
    owner = bus.first(bus.call('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                              'GetNameOwner', ('org.kde.KWin',)), lambda p: bus.get_string(p, None))
    loop = bus.loop_new(None, 0)
    stopped = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    def parent_pipe():
        os.read(sys.stdin.fileno(), 64)
        stopped.set()
    threading.Thread(target=parent_pipe, daemon=True).start()
    xml = f'<node><interface name="{INTERFACE}"><method name="Update"><arg type="s" direction="in"/>' \
          '<arg type="s" direction="in"/></method></interface></node>'
    received = [time.monotonic(), False]
    node = bus.node_new(xml.encode(), None)
    method_type = C.CFUNCTYPE(None, P, S, S, S, S, P, P, P)
    @method_type
    def update(connection, sender, path, interface, method, params, invocation, user):
        if sender != owner:
            bus.error_reply(invocation, b'org.freedesktop.DBus.Error.AccessDenied', b'KWin only')
            return
        try:
            values = []
            for i in range(2):
                part = bus.child(params, i)
                values.append(float(bus.get_string(part, None)))
                bus.unref(part)
            received[:] = [time.monotonic(), True]
            print('POINT %.3f %.3f' % tuple(values), flush=True)
            bus.reply(invocation, None)
        except (OSError, ValueError):
            stopped.set()
            bus.reply(invocation, None)
    class VTable(C.Structure):
        _fields_ = [('method', P), ('get_property', P), ('set_property', P), ('padding', P * 8)]
    table = VTable(C.cast(update, P), None, None, (P * 8)())
    registration = bus.register(bus.connection, OBJECT.encode(), bus.node_lookup(node, INTERFACE.encode()),
                                C.byref(table), None, None, None)
    if not registration:
        raise RuntimeError('指针桥接注册失败')
    name = f'spw-island-pointer-{os.getpid()}'
    filename, loaded, timer = None, False, None
    last_point_start = time.monotonic()
    tick_type = C.CFUNCTYPE(I, P)
    @tick_type
    def tick(_):
        if stopped.is_set() or not Path(f'/proc/{parent}').exists():
            bus.loop_quit(loop)
            return 1
        # Startup failures do not leave a dormant helper/script running indefinitely.
        if (not received[1] and time.monotonic() - last_point_start > 5) or (
                received[1] and time.monotonic() - received[0] > 2):
            print('UNAVAILABLE KWin 未提供指针位置', flush=True)
            bus.loop_quit(loop)
            return 1
        return 1
    try:
        with tempfile.NamedTemporaryFile('w', prefix='spw-island-kwin-', suffix='.js', delete=False) as file:
            filename = file.name
            file.write(script_source(bus.unique(bus.connection).decode(), name))
        script_id = bus.first(bus.call('org.kde.KWin', '/Scripting', 'org.kde.kwin.Scripting',
                                       'loadScript', (filename, name)), bus.int32)
        if script_id < 0:
            raise RuntimeError('KWin 拒绝加载指针脚本')
        loaded = True
        # Plasma 6 uses the qualified path; Plasma 5 used /<id>.
        try:
            result = bus.call('org.kde.KWin', f'/Scripting/Script{script_id}', 'org.kde.kwin.Script', 'run')
        except RuntimeError:
            result = bus.call('org.kde.KWin', f'/{script_id}', 'org.kde.kwin.Script', 'run')
        bus.unref(result)
        timer = bus.timeout(200, tick, None)
        bus.loop_run(loop)
    finally:
        if timer:
            bus.remove_source(timer)
        if loaded:
            try:
                bus.unref(bus.call('org.kde.KWin', '/Scripting', 'org.kde.kwin.Scripting', 'unloadScript', (name,)))
            except RuntimeError:
                pass
        if filename:
            Path(filename).unlink(missing_ok=True)
        bus.unregister(bus.connection, registration)
        bus.loop_unref(loop)


def run_gnome(parent):
    bus = Bus()
    stopped = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    def parent_pipe():
        os.read(sys.stdin.fileno(), 64)
        stopped.set()
    threading.Thread(target=parent_pipe, daemon=True).start()
    while not stopped.is_set() and Path(f'/proc/{parent}').exists():
        try:
            result = bus.call('org.gnome.Shell', OBJECT, INTERFACE, 'GetPointer', signature='(dd)')
        except RuntimeError as error:
            raise RuntimeError('GNOME 悬停隐藏需启用 SPW Island Pointer 扩展；首次安装后请重新登录') from error
        try:
            values = []
            for i in range(2):
                part = bus.child(result, i)
                try:
                    values.append(bus.double(part))
                finally:
                    bus.unref(part)
            print('POINT %.3f %.3f' % tuple(values), flush=True)
        finally:
            bus.unref(result)
        stopped.wait(.05)


if __name__ == '__main__':
    try:
        (run_gnome if len(sys.argv) > 2 and sys.argv[2] == 'gnome' else run)(int(sys.argv[1]))
    except (OSError, RuntimeError, ValueError) as error:
        print(f'UNAVAILABLE 悬停隐藏不可用：{error}', flush=True)
        sys.exit(1)
