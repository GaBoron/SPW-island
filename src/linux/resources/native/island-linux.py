# SPDX-License-Identifier: GPL-3.0-only
"""Linux desktop bridge. Python stdlib only; uses the installed JVM/GTK shared libraries."""
import ctypes as C
import os
import sys
import threading

P = C.c_void_p
I = C.c_int
S = C.c_char_p


def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype = result
    fn.argtypes = args
    return fn


def load_library(soname):
    """Keep the runtime's lookup first, then try the usual Linux x64 library locations."""
    try:
        return C.CDLL(soname)
    except OSError as error:
        failures = [str(error)]
    # An installed library may be absent from the loader cache visible to SPW.
    # Stay inside this process's filesystem; do not mix in /run/host libraries.
    for directory in ("/usr/lib", "/usr/lib64", "/usr/lib/x86_64-linux-gnu",
                      "/lib", "/lib64", "/lib/x86_64-linux-gnu"):
        path = os.path.join(directory, soname)
        if os.path.isfile(path):
            try:
                return C.CDLL(path)
            except OSError as error:
                failures.append(str(error))
    raise OSError("; ".join(failures))


def tray_libraries():
    glib = load_library("libglib-2.0.so.0")
    obj = load_library("libgobject-2.0.so.0")
    failures = []
    # Both GTK versions expose the menu/StatusIcon API used below. Keep working
    # when this process can see GTK 2 but not the system's GTK 3 installation.
    for soname, indicators in (
        ("libgtk-3.so.0", ("libayatana-appindicator3.so.1", "libappindicator3.so.1")),
        ("libgtk-x11-2.0.so.0", ("libayatana-appindicator.so.1", "libappindicator.so.1")),
    ):
        try:
            return load_library(soname), obj, glib, indicators
        except OSError as error:
            failures.append(str(error))
    raise RuntimeError("当前 SPW 运行环境无法加载 GTK 3 或 GTK 2；请检查该运行环境中的 GTK 库。" +
                       "\n" + "\n".join(failures))


def launch_jvm(home, classpath, main_class, application_name="灵动词岛 for SPW"):
    # Standard JNI Invocation API, with a fresh OS thread and its own JVM/EDT/Java2D queue.
    class Option(C.Structure):
        _fields_ = [("text", S), ("extra", P)]

    class InitArgs(C.Structure):
        _fields_ = [("version", I), ("count", I), ("options", C.POINTER(Option)), ("ignore", C.c_ubyte)]

    options = ["-Djava.class.path=" + classpath, "-Djava.home=" + home,
               "-Dsun.java.command=" + application_name,
               "--enable-native-access=ALL-UNNAMED", "-Dsun.java2d.opengl=false", "-Dsun.java2d.xrender=true", "-Xms16m", "-Xmx192m"]
    encoded = [v.encode("utf-8") for v in options]
    values = (Option * len(encoded))(*(Option(v, None) for v in encoded))
    init = InitArgs(0x00010008, len(values), values, 0)  # JNI_VERSION_1_8 (stable invocation ABI)
    lib = C.CDLL(os.path.join(home, "lib", "server", "libjvm.so"), mode=C.RTLD_GLOBAL)
    create = bind(lib, "JNI_CreateJavaVM", I, C.POINTER(P), C.POINTER(P), P)
    vm, env = P(), P()
    if create(C.byref(vm), C.byref(env), C.byref(init)) != 0:
        raise RuntimeError("Unable to initialize the bundled JVM")
    table = C.cast(env, C.POINTER(C.POINTER(P))).contents

    def jni(index, result, *params):
        return C.CFUNCTYPE(result, P, *params)(table[index])

    find = jni(6, P, S)
    exception = jni(15, P)
    describe = jni(16, None)
    method = jni(113, P, P, S, S)
    call = jni(143, None, P, P, P)  # CallStaticVoidMethodA, jvalue[]
    array = jni(172, P, I, P, P)
    cls = find(env, main_class.encode("utf-8"))
    if not cls:
        describe(env)
        raise RuntimeError("UI entry point is not on the classpath")
    mid = method(env, cls, b"main", b"([Ljava/lang/String;)V")
    if not mid:
        describe(env)
        raise RuntimeError("Missing UI main method")
    strings = find(env, b"java/lang/String")
    argv = array(env, 0, strings, None)
    # A jvalue is an eight-byte union, also on the supported Linux x64 target.
    arguments = (P * 1)(argv)
    call(env, cls, mid, arguments)
    failed = bool(exception(env))
    if failed:
        describe(env)
    vm_table = C.cast(vm, C.POINTER(C.POINTER(P))).contents
    C.CFUNCTYPE(I, P)(vm_table[3])(vm)  # DestroyJavaVM
    if failed:
        raise RuntimeError("UI main failed")


def native_tray(name, icon_path, libraries):
    """Persistent system tray; the GTK main loop alone owns widgets and native callbacks."""
    import queue
    gtk, obj, glib, indicator_libraries = libraries
    bind(glib, "g_set_prgname", None, S)(b"spw-lyrics-island")
    bind(glib, "g_set_application_name", None, S)(name.encode("utf-8"))
    if not bind(gtk, "gtk_init_check", I, P, P)(None, None):
        raise RuntimeError("GTK cannot connect to the desktop tray")
    incoming = queue.Queue(maxsize=1)

    def publish(value):
        try:
            incoming.get_nowait()
        except queue.Empty:
            pass
        incoming.put(value)

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
    first = incoming.get()
    if first is None:
        return
    connect = bind(obj, "g_signal_connect_data", C.c_ulong, P, S, P, P, P, I)
    callback_type = C.CFUNCTYPE(None, P, P)
    quit_loop = bind(gtk, "gtk_main_quit", None)
    menu = None
    item_callbacks = []
    indicator = None
    indicator_lib = None
    status_icon = None

    def create_menu(rows):
        callbacks = []
        result = bind(gtk, "gtk_menu_new", P)()
        bind(obj, "g_object_ref_sink", P, P)(result)
        for kind, command, checked, label in rows:
            if kind == "SEPARATOR":
                widget = bind(gtk, "gtk_separator_menu_item_new", P)()
            elif kind == "TOGGLE":
                widget = bind(gtk, "gtk_check_menu_item_new_with_label", P, S)(label.encode("utf-8"))
                bind(gtk, "gtk_check_menu_item_set_active", None, P, I)(widget, int(checked))
            else:
                widget = bind(gtk, "gtk_menu_item_new_with_label", P, S)(label.encode("utf-8"))
            if kind in ("TITLE", "NOTE"):
                bind(gtk, "gtk_widget_set_sensitive", None, P, I)(widget, 0)
            elif kind != "SEPARATOR":
                callback = callback_type(lambda widget, data, value=command: print(value, flush=True))
                callbacks.append(callback)
                connect(widget, b"activate", callback, None, None, 0)
            bind(gtk, "gtk_menu_shell_append", None, P, P)(result, widget)
        # Show children, not the top-level GtkMenu, until the tray requests it.
        bind(gtk, "gtk_widget_show_all", None, P)(result)
        bind(gtk, "gtk_widget_hide", None, P)(result)
        return result, callbacks

    menu, item_callbacks = create_menu(first)
    for library in indicator_libraries:
        try:
            candidate = load_library(library)
            indicator = bind(candidate, "app_indicator_new", P, S, S, I)(
                b"spw-lyrics-island", icon_path.encode("utf-8"), 0)
            if indicator:
                indicator_lib = candidate
                break
        except (OSError, AttributeError):
            continue
    callbacks = []
    if indicator:
        bind(indicator_lib, "app_indicator_set_title", None, P, S)(indicator, name.encode("utf-8"))
        bind(indicator_lib, "app_indicator_set_menu", None, P, P)(indicator, menu)
        bind(indicator_lib, "app_indicator_set_status", None, P, I)(indicator, 1)  # ACTIVE
    else:
        status_icon = bind(gtk, "gtk_status_icon_new_from_file", P, S)(icon_path.encode("utf-8"))
        bind(gtk, "gtk_status_icon_set_title", None, P, S)(status_icon, name.encode("utf-8"))
        bind(gtk, "gtk_status_icon_set_tooltip_text", None, P, S)(status_icon, name.encode("utf-8"))
        popup_type = C.CFUNCTYPE(None, P, C.c_uint, C.c_uint, P)

        def popup(icon, button, timestamp, data):
            bind(gtk, "gtk_menu_popup", None, P, P, P, P, P, C.c_uint, C.c_uint)(
                menu, None, None, C.cast(gtk.gtk_status_icon_position_menu, P), icon, button, timestamp)

        popup_cb = popup_type(popup)
        activate_cb = callback_type(lambda icon, data: popup(icon, 0, 0, data))
        callbacks.extend((popup_cb, activate_cb))
        connect(status_icon, b"popup-menu", popup_cb, None, None, 0)
        connect(status_icon, b"activate", activate_cb, None, None, 0)
        bind(gtk, "gtk_status_icon_set_visible", None, P, I)(status_icon, 1)
    parent = os.getppid()
    pending = []
    timer_type = C.CFUNCTYPE(I, P)

    def update(data):
        nonlocal menu, item_callbacks
        if os.getppid() != parent:
            quit_loop()
            return 0
        try:
            value = incoming.get_nowait()
            if value is None:
                quit_loop()
                return 0
            pending[:] = [value]
        except queue.Empty:
            pass
        if pending and not bind(gtk, "gtk_widget_get_visible", I, P)(menu):
            replacement, new_callbacks = create_menu(pending.pop())
            old = menu
            menu = replacement
            if indicator:
                bind(indicator_lib, "app_indicator_set_menu", None, P, P)(indicator, menu)
            bind(gtk, "gtk_widget_destroy", None, P)(old)
            bind(obj, "g_object_unref", None, P)(old)
            item_callbacks = new_callbacks
        return 1

    update_cb = timer_type(update)
    callbacks.append(update_cb)
    bind(glib, "g_timeout_add", C.c_uint, C.c_uint, P, P)(150, update_cb, None)
    try:
        bind(gtk, "gtk_main", None)()
    finally:
        if indicator:
            bind(indicator_lib, "app_indicator_set_status", None, P, I)(indicator, 0)
            bind(obj, "g_object_unref", None, P)(indicator)
        if status_icon:
            bind(gtk, "gtk_status_icon_set_visible", None, P, I)(status_icon, 0)
            bind(obj, "g_object_unref", None, P)(status_icon)
        bind(gtk, "gtk_widget_destroy", None, P)(menu)
        bind(obj, "g_object_unref", None, P)(menu)


def run_tray(name, icon_path, dbus_path, width, height, pixels):
    try:
        libraries = tray_libraries()
    except (OSError, RuntimeError) as gtk_error:
        # Steam Runtime can omit both GTK versions. Use the desktop's tray over
        # its session bus, without importing host libraries into the container.
        print("[SPW Island] GTK 不可用，尝试 D-Bus 托盘。", file=sys.stderr, flush=True)
        import runpy
        try:
            runpy.run_path(dbus_path)["native_tray"](name, width, height, pixels, load_library)
        except (OSError, RuntimeError) as dbus_error:
            raise RuntimeError(str(gtk_error) + "\nD-Bus 托盘：" + str(dbus_error)) from dbus_error
    else:
        native_tray(name, icon_path, libraries)


if __name__ == "__main__":
    if sys.argv[1] == "jvm":
        failures = []

        def run():
            try:
                launch_jvm(*sys.argv[2:6])
            except BaseException as error:
                failures.append(error)
                print(error, file=sys.stderr)

        threading.stack_size(4 * 1024 * 1024)
        thread = threading.Thread(target=run)
        thread.start()
        thread.join()
        sys.exit(1 if failures else 0)
    elif sys.argv[1] == "tray":
        try:
            run_tray(*sys.argv[2:8])
        except (OSError, RuntimeError) as error:
            print("[SPW Island] Linux 托盘不可用；词岛仍可运行，设置可从 SPW 插件设置页修改。\n" +
                  str(error), file=sys.stderr, flush=True)
            sys.exit(1)
    else:
        raise SystemExit("Unknown helper mode")
