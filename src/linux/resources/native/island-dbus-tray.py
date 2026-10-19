# SPDX-License-Identifier: GPL-3.0-only
"""StatusNotifierItem and DBusMenu through GIO, without GTK or Python packages."""
import base64
import ctypes as C
import json
import sys
import threading

P, I, U, S = C.c_void_p, C.c_int, C.c_uint, C.c_char_p
ITEM = b"org.kde.StatusNotifierItem"
WATCHER = b"org.kde.StatusNotifierWatcher"
MENU = b"com.canonical.dbusmenu"
ITEM_PATH, MENU_PATH = b"/StatusNotifierItem", b"/Menu"


def interface_xml(name, methods, properties, signals=()):
    def arguments(signatures, direction):
        return "".join('<arg type="%s" direction="%s"/>' % (s, direction) for s in signatures)
    return '<node><interface name="%s">%s%s%s</interface></node>' % (
        name.decode(),
        "".join('<method name="%s">%s%s</method>' %
                (method, arguments(ins, "in"), arguments(outs, "out"))
                for method, ins, outs in methods),
        "".join('<property name="%s" type="%s" access="read"/>' % (key, value[0])
                for key, value in properties.items()),
        "".join('<signal name="%s">%s</signal>' % (signal, arguments(args, "out"))
                for signal, args in signals))


def text(value):
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, tuple):
        return "(" + ", ".join(map(text, value)) + ("," if len(value) == 1 else "") + ")"
    if isinstance(value, list):
        return "[" + ", ".join(map(text, value)) + "]"
    return str(value)


def property_text(properties, names=()):
    return "{" + ", ".join(text(key) + ": <@" + sig + " " + text(value) + ">"
                           for key, (sig, value) in properties.items()
                           if not names or key in names) + "}"


class MenuModel:
    """Keep wire command IDs separate from unique DBusMenu row IDs."""
    def __init__(self, rows):
        self.rows = {index: row for index, row in enumerate(rows, 1)}

    def properties(self, item):
        if item == 0:
            return {"children-display": ("s", "submenu")}
        kind, command, checked, label = self.rows[item]
        if kind == "SEPARATOR":
            return {"type": ("s", "separator")}
        # DBusMenu labels use underscores as mnemonic markers.
        result = {"label": ("s", label.replace("_", "__")),
                  "enabled": ("b", kind in ("ACTION", "TOGGLE")), "visible": ("b", True)}
        if kind == "TOGGLE":
            result.update({"toggle-type": ("s", "checkmark"), "toggle-state": ("i", int(checked))})
        return result

    def layout(self, item, depth, names):
        children = []
        if item == 0 and depth != 0:
            children = ["<" + self.layout(child, 0, names) + ">" for child in self.rows]
        return "(%d, %s, @av [%s])" % (item, property_text(self.properties(item), names), ", ".join(children))

    def event(self, item, event):
        if item != 0 and item not in self.rows:
            return False
        if event == "clicked" and item in self.rows:
            kind, command, checked, label = self.rows[item]
            if kind in ("ACTION", "TOGGLE"):
                print(int(command), flush=True)
        return True


class GioBus:
    """Own GIO callbacks and non-floating GVariants at the C ABI boundary."""
    def __init__(self, load_library):
        self.glib = load_library("libglib-2.0.so.0")
        self.gio = load_library("libgio-2.0.so.0")
        self.obj = load_library("libgobject-2.0.so.0")
        self.callbacks, self.registrations = [], []
        self.connection = self.loop = None

    @staticmethod
    def bind(lib, name, result, *args):
        fn = getattr(lib, name)
        fn.restype, fn.argtypes = result, args
        return fn

    def gl(self, name, result, *args):
        return self.bind(self.glib, name, result, *args)

    def io(self, name, result, *args):
        return self.bind(self.gio, name, result, *args)

    def error(self, error, message):
        if error:
            class Error(C.Structure):
                _fields_ = [("domain", U), ("code", I), ("message", S)]
            message += ": " + C.cast(error, C.POINTER(Error)).contents.message.decode("utf-8", "replace")
            self.gl("g_error_free", None, P)(error)
        raise RuntimeError(message)

    def variant(self, signature, literal):
        error = P()
        value = self.gl("g_variant_parse", P, S, S, P, P, C.POINTER(P))(
            signature.encode(), literal.encode(), None, None, C.byref(error))
        if not value:
            self.error(error, "Invalid tray D-Bus value")
        return self.gl("g_variant_take_ref", P, P)(value)

    def unpack(self, value):
        kind = chr(self.gl("g_variant_classify", I, P)(value))
        if kind in "sog":
            return self.gl("g_variant_get_string", S, P, P)(value, None).decode()
        if kind in "iu":
            return self.gl("g_variant_get_" + ("int32" if kind == "i" else "uint32"), I if kind == "i" else U, P)(value)
        if kind == "b":
            return bool(self.gl("g_variant_get_boolean", I, P)(value))
        if kind == "v":
            # Event payloads are desktop-specific and do not affect menu actions.
            return None
        result = []
        for index in range(self.gl("g_variant_n_children", C.c_size_t, P)(value)):
            child = self.gl("g_variant_get_child_value", P, P, C.c_size_t)(value, index)
            try:
                result.append(self.unpack(child))
            finally:
                self.gl("g_variant_unref", None, P)(child)
        return result

    def reply(self, invocation, signature, literal):
        value = self.variant(signature, literal)
        try:
            self.io("g_dbus_method_invocation_return_value", None, P, P)(invocation, value)
        finally:
            self.gl("g_variant_unref", None, P)(value)

    def emit(self, path, interface, signal, signature, literal):
        value = self.variant(signature, literal)
        try:
            self.io("g_dbus_connection_emit_signal", I, P, S, S, S, S, P, P)(
                self.connection, None, path, interface, signal, value, None)
        finally:
            self.gl("g_variant_unref", None, P)(value)

    def connect(self):
        error = P()
        self.connection = self.io("g_bus_get_sync", P, I, P, C.POINTER(P))(2, None, C.byref(error))
        if not self.connection:
            self.error(error, "无法连接桌面 D-Bus 会话")
        self.loop = self.gl("g_main_loop_new", P, P, I)(None, 0)

    def export(self, path, interface, methods, properties, handler, signals=()):
        method_type = C.CFUNCTYPE(None, P, S, S, S, S, P, P, P)
        property_type = C.CFUNCTYPE(P, P, S, S, S, S, P, P)

        def method(connection, sender, path, interface, name, parameters, invocation, data):
            try:
                signature, literal = handler(name.decode(), self.unpack(parameters))
                self.reply(invocation, signature, literal)
            except Exception as error:
                self.io("g_dbus_method_invocation_return_dbus_error", None, P, S, S)(
                    invocation, b"com.canonical.dbusmenu.Error.Failed", str(error).encode())

        def get_property(connection, sender, path, interface, name, error, data):
            signature, value = properties[name.decode()]
            return self.variant(signature, text(value))

        class VTable(C.Structure):
            _fields_ = [("method", method_type), ("property", property_type),
                        ("set_property", P), ("padding", P * 8)]

        callbacks = VTable(method_type(method), property_type(get_property), None)
        self.callbacks.append(callbacks)
        error = P()
        node = self.io("g_dbus_node_info_new_for_xml", P, S, C.POINTER(P))(
            interface_xml(interface, methods, properties, signals).encode(), C.byref(error))
        if not node:
            self.error(error, "Invalid tray D-Bus interface")
        try:
            info = self.io("g_dbus_node_info_lookup_interface", P, P, S)(node, interface)
            registration = self.io("g_dbus_connection_register_object", U, P, S, P, P, P, P, C.POINTER(P))(
                self.connection, path, info, C.byref(callbacks), None, None, C.byref(error))
            if not registration:
                self.error(error, "Unable to export tray D-Bus interface")
            self.registrations.append(registration)
        finally:
            self.io("g_dbus_node_info_unref", None, P)(node)

    def close(self):
        for registration in self.registrations:
            self.io("g_dbus_connection_unregister_object", I, P, U)(self.connection, registration)
        if self.loop:
            self.gl("g_main_loop_unref", None, P)(self.loop)
        if self.connection:
            self.bind(self.obj, "g_object_unref", None, P)(self.connection)


def native_tray(name, width, height, pixels, load_library):
    rows = []
    for line in sys.stdin:
        if line.rstrip("\n") == "END":
            break
        rows.append(line.rstrip("\n").split("\t", 3))
    else:
        return
    width, height = int(width), int(height)
    argb = base64.b64decode(pixels, validate=True)
    if not (0 < width <= 256 and 0 < height <= 256 and len(argb) == width * height * 4):
        raise RuntimeError("Invalid tray icon pixels")
    bus = GioBus(load_library)
    watch = 0
    failures = []
    try:
        bus.connect()
        model, revision = MenuModel(rows), 1

        def menu_method(method, args):
            if method == "GetLayout":
                return "(u(ia{sv}av))", "(%d, %s)" % (revision, model.layout(*args))
            if method == "GetGroupProperties":
                ids, names = args
                values = ["(%d, %s)" % (item, property_text(model.properties(item), names))
                          for item in (ids or [0, *model.rows]) if item == 0 or item in model.rows]
                return "(a(ia{sv}))", "([" + ", ".join(values) + "],)"
            if method == "GetProperty":
                sig, value = model.properties(args[0])[args[1]]
                return "(v)", "(<@%s %s>,)" % (sig, text(value))
            if method == "Event":
                model.event(args[0], args[1])
                return "()", "()"
            if method == "EventGroup":
                invalid = [event[0] for event in args[0] if not model.event(event[0], event[1])]
                return "(ai)", "(" + text(invalid) + ",)"
            if method == "AboutToShow":
                return "(b)", "(false,)"
            if method == "AboutToShowGroup":
                invalid = [item for item in args[0] if item != 0 and item not in model.rows]
                return "(aiai)", "([], " + text(invalid) + ")"
            raise ValueError("Unknown menu method")

        bus.export(MENU_PATH, MENU, [
            ("GetLayout", ["i", "i", "as"], ["u", "(ia{sv}av)"]),
            ("GetGroupProperties", ["ai", "as"], ["a(ia{sv})"]),
            ("GetProperty", ["i", "s"], ["v"]), ("Event", ["i", "s", "v", "u"], []),
            ("EventGroup", ["a(isvu)"], ["ai"]), ("AboutToShow", ["i"], ["b"]),
            ("AboutToShowGroup", ["ai"], ["ai", "ai"])],
            {"Version": ("u", 3), "TextDirection": ("s", "ltr"), "Status": ("s", "normal"),
             "IconThemePath": ("as", [])}, menu_method, [("LayoutUpdated", ["u", "i"])])
        bus.export(ITEM_PATH, ITEM, [
            ("ContextMenu", ["i", "i"], []), ("Activate", ["i", "i"], []),
            ("SecondaryActivate", ["i", "i"], []), ("Scroll", ["i", "s"], [])],
            {"Category": ("s", "ApplicationStatus"), "Id": ("s", "spw-lyrics-island"),
             "Title": ("s", name), "Status": ("s", "Active"), "WindowId": ("i", 0),
             "IconThemePath": ("s", ""), "IconName": ("s", ""),
             "IconPixmap": ("a(iiay)", [(width, height, list(argb))]),
             "OverlayIconName": ("s", ""), "OverlayIconPixmap": ("a(iiay)", []),
             "AttentionIconName": ("s", ""), "AttentionIconPixmap": ("a(iiay)", []),
             "AttentionMovieName": ("s", ""), "ToolTip": ("(sa(iiay)ss)", ("", [], name, "")),
             "ItemIsMenu": ("b", True), "Menu": ("o", MENU_PATH.decode())}, lambda *_: ("()", "()"))

        finish_type = C.CFUNCTYPE(None, P, P, P)

        def registered(connection, result, data):
            error = P()
            value = bus.io("g_dbus_connection_call_finish", P, P, P, C.POINTER(P))(
                connection, result, C.byref(error))
            if value:
                bus.gl("g_variant_unref", None, P)(value)
            else:
                try:
                    bus.error(error, "桌面托盘注册失败")
                except RuntimeError as failure:
                    failures.append(failure)
                bus.gl("g_main_loop_quit", None, P)(bus.loop)

        registered_cb = finish_type(registered)
        appeared_type = C.CFUNCTYPE(None, P, S, S, P)
        vanished_type = C.CFUNCTYPE(None, P, S, P)
        seen = False

        def appeared(connection, name, owner, data):
            nonlocal seen
            seen = True
            value = bus.variant("(s)", text((ITEM_PATH.decode(),)))
            try:
                bus.io("g_dbus_connection_call", None, P, S, S, S, S, P, P, I, I, P, finish_type, P)(
                    connection, WATCHER, b"/StatusNotifierWatcher", WATCHER,
                    b"RegisterStatusNotifierItem", value, None, 0, 5000, None, registered_cb, None)
            finally:
                bus.gl("g_variant_unref", None, P)(value)

        def vanished(connection, name, data):
            if not seen:
                failures.append(RuntimeError("桌面未提供 StatusNotifierWatcher；请启用系统托盘 / AppIndicator 支持"))
                bus.gl("g_main_loop_quit", None, P)(bus.loop)

        appeared_cb, vanished_cb = appeared_type(appeared), vanished_type(vanished)
        watch = bus.io("g_bus_watch_name_on_connection", U, P, S, I, appeared_type, vanished_type, P, P)(
            bus.connection, WATCHER, 0, appeared_cb, vanished_cb, None, None)
        idle_type = C.CFUNCTYPE(I, P)
        lock, pending = threading.Lock(), []

        def update(data):
            nonlocal model, revision
            with lock:
                value = pending.pop()
            if value is None:
                bus.gl("g_main_loop_quit", None, P)(bus.loop)
            else:
                model, revision = MenuModel(value), revision + 1
                bus.emit(MENU_PATH, MENU, b"LayoutUpdated", "(ui)", text((revision, 0)))
            return 0

        update_cb = idle_type(update)

        def publish(value):
            with lock:
                schedule = not pending
                pending[:] = [value]
                if schedule:
                    bus.gl("g_idle_add", U, idle_type, P)(update_cb, None)

        def read_updates():
            rows = []
            try:
                for line in sys.stdin:
                    if line.rstrip("\n") == "END":
                        publish(rows)
                        rows = []
                    else:
                        rows.append(line.rstrip("\n").split("\t", 3))
            finally:
                publish(None)

        threading.Thread(target=read_updates, daemon=True).start()
        # Both performance profiles use bus events and pipe updates, without a polling timer.
        bus.gl("g_main_loop_run", None, P)(bus.loop)
        if failures:
            raise failures[0]
    finally:
        if watch:
            bus.io("g_bus_unwatch_name", None, U)(watch)
        bus.close()
