// SPDX-License-Identifier: GPL-3.0-only
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import {GlassActor} from './renderer.js';

const INTERFACE = `<node><interface name="io.github.gaboron.SpwIsland.Glass">
    <method name="UpdateV3"><arg type="s" direction="in"/><arg type="s" direction="out"/></method>
    <method name="Release"/>
</interface></node>`;

/** Optional service for the isolated UI process; no desktop-wide window rules or input hooks. */
export default class SpwIslandGlass extends Extension {
    enable() {
        this._state = null;
        this._view = null;
        this._before = global.stage.connect('before-paint', (_stage, view) => { this._view = view; });
        this._after = global.stage.connect('after-paint', () => { this._view = null; });
        this._sources = {};
        for (const name of ['contour', 'blur', 'glass']) {
            const [ok, contents] = this.dir.get_child(`${name}.glsl`).load_contents(null);
            if (!ok) throw new Error(`SPW Island Glass: cannot read ${name}.glsl`);
            this._sources[name] = new TextDecoder().decode(contents);
        }
        this._service = Gio.DBusExportedObject.wrapJSObject(INTERFACE, {
            UpdateV3Async: ([text], invocation) => this._update(text, invocation),
            ReleaseAsync: (_params, invocation) => {
                if (this._state?.sender === invocation.get_sender()) this._release();
                invocation.return_value(new GLib.Variant('()', []));
            },
        });
        this._service.export(Gio.DBus.session, '/io/github/gaboron/SpwIsland/Glass');
    }

    _update(text, invocation) {
        let payload;
        try {
            if (text.length > 32000) throw new Error('轮廓数据过大');
            payload = JSON.parse(text);
            if (!Number.isSafeInteger(payload.pid) || payload.pid <= 0 ||
                typeof payload.title !== 'string' || payload.title.length > 200 ||
                !Number.isFinite(payload.maskAlpha) || payload.maskAlpha <= 0 || payload.maskAlpha > 1 ||
                !Number.isFinite(payload.opacity) || payload.opacity < 0 || payload.opacity > 1 ||
                !Array.isArray(payload.points) || payload.points.length < 3 || payload.points.length > 256 ||
                !payload.points.every(p => Array.isArray(p) && p.length === 2 &&
                    p.every(v => Number.isFinite(v) && v >= -2 && v <= 3)))
                throw new Error('轮廓数据无效');
        } catch (error) {
            invocation.return_dbus_error('org.freedesktop.DBus.Error.InvalidArgs', error.message);
            return;
        }
        const sender = invocation.get_sender();
        if (this._state?.sender === sender && this._state.pid === payload.pid) {
            this._present(payload, invocation);
            return;
        }
        // The helper must be a direct child of the UI process owning the target window.
        Gio.DBus.session.call('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
            'GetConnectionUnixProcessID', new GLib.Variant('(s)', [sender]), new GLib.VariantType('(u)'),
            Gio.DBusCallFlags.NONE, 750, null, (connection, result) => {
                try {
                    const [pid] = connection.call_finish(result).deep_unpack();
                    const [ok, bytes] = Gio.File.new_for_path(`/proc/${pid}/status`).load_contents(null);
                    const parent = ok ? /^PPid:\s+(\d+)$/m.exec(new TextDecoder().decode(bytes)) : null;
                    if (!parent || Number(parent[1]) !== payload.pid)
                        throw new Error('桥接进程不属于词岛界面');
                    if (!this._service) throw new Error('配套扩展已关闭');
                    this._present(payload, invocation);
                } catch (error) {
                    invocation.return_dbus_error('org.freedesktop.DBus.Error.AccessDenied', error.message);
                }
            });
    }

    _present(payload, invocation) {
        try {
            let state = this._state;
            if (state && (state.sender !== invocation.get_sender() || state.pid !== payload.pid)) {
                invocation.return_value(new GLib.Variant('(s)', ['WAIT 已有词岛界面连接']));
                return;
            }
            if (!state) {
                const owner = global.get_window_actors().find(actor => {
                    const window = actor.get_meta_window();
                    return window?.get_pid() === payload.pid && window.get_title() === payload.title;
                });
                if (!owner || owner.is_destroyed()) {
                    invocation.return_value(new GLib.Variant('(s)', ['WAIT 等待词岛窗口']));
                    return;
                }
                const surface = owner.get_first_child();
                if (!surface) throw new Error('词岛窗口尚无合成表面');
                const glass = new GlassActor(owner, this._sources, () => this._view);
                owner.insert_child_below(glass, surface);
                const destroy = owner.connect('destroy', () => this._release());
                state = {owner, glass, destroy, sender: invocation.get_sender(), pid: payload.pid};
                this._state = state;
                this._lease = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 1000, () => {
                    if (!this._state || GLib.get_monotonic_time() - this._state.updated > 2_500_000) {
                        this._lease = 0;
                        this._release();
                        return GLib.SOURCE_REMOVE;
                    }
                    return GLib.SOURCE_CONTINUE;
                });
            }
            state.updated = GLib.get_monotonic_time();
            state.glass.update(payload);
            const reply = state.glass.failure ? `WAIT ${state.glass.failure}` :
                state.glass.ready && GLib.get_monotonic_time() - state.glass.lastPaint < 1_500_000
                    ? 'READY' : 'WAIT 等待合成器首帧';
            invocation.return_value(new GLib.Variant('(s)', [reply]));
        } catch (error) {
            this._release();
            invocation.return_value(new GLib.Variant('(s)', [`WAIT ${error.message}`]));
            console.error(`SPW Island Glass: ${error.stack ?? error}`);
        }
    }

    _release() {
        const state = this._state;
        this._state = null;
        if (this._lease) { GLib.Source.remove(this._lease); this._lease = 0; }
        if (state) {
            state.owner.disconnect(state.destroy);
            state.glass.destroy();
        }
    }

    disable() {
        this._service?.unexport();
        this._service = null;
        this._release();
        if (this._before) global.stage.disconnect(this._before);
        if (this._after) global.stage.disconnect(this._after);
        this._before = 0;
        this._after = 0;
        this._view = null;
        this._sources = null;
    }
}
