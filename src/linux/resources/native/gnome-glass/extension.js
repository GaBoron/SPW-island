// SPDX-License-Identifier: GPL-3.0-only
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import {GlassSurface} from './surface.js';

const INTERFACE = `<node><interface name="io.github.gaboron.SpwIsland.Glass">
    <method name="UpdateManaged"><arg type="s" direction="in"/><arg type="s" direction="out"/></method>
    <method name="Reload"><arg type="s" direction="in"/><arg type="s" direction="out"/></method>
    <method name="Release"/>
</interface></node>`;

/** Optional service for the isolated UI process; no desktop-wide window rules or input hooks. */
export default class SpwIslandGlass extends Extension {
    enable() {
        this._state = null;
        this._view = null;
        this._generation = (this._generation ?? 0) + 1;
        this._modules = new Map();
        this._desired = null;
        this._before = global.stage.connect('before-paint', (_stage, view) => { this._view = view; });
        this._after = global.stage.connect('after-paint', () => { this._view = null; });
        this._service = Gio.DBusExportedObject.wrapJSObject(INTERFACE, {
            UpdateManagedAsync: ([text], invocation) => this._update(text, invocation),
            ReloadAsync: ([revision], invocation) => {
                const generation = this._generation;
                this._loadRenderer(revision, true).then(backend => {
                    this._desired = backend;
                    this._state?.glass.stage(backend.revision, backend, true);
                    invocation.return_value(new GLib.Variant('(s)', ['LOADED']));
                }).catch(error => {
                    if (generation === this._generation && this._state)
                        this._state.glass.notice = `热更新失败，继续使用当前材质：${error.message}`;
                    invocation.return_value(new GLib.Variant('(s)', [`WAIT ${error.message}`]));
                });
            },
            ReleaseAsync: (_params, invocation) => {
                if (this._state?.sender === invocation.get_sender()) this._release();
                invocation.return_value(new GLib.Variant('()', []));
            },
        });
        this._service.export(Gio.DBus.session, '/io/github/gaboron/SpwIsland/Glass');
    }

    _read(file, limit) {
        const info = file.query_info('standard::type,standard::size,standard::is-symlink',
            Gio.FileQueryInfoFlags.NOFOLLOW_SYMLINKS, null);
        if (info.get_is_symlink() || info.get_file_type() !== Gio.FileType.REGULAR || info.get_size() > limit)
            throw new Error('渲染模块文件类型或大小不正确');
        const [ok, bytes] = file.load_contents(null);
        if (!ok || bytes.length > limit) throw new Error('无法读取渲染模块');
        return new TextDecoder().decode(bytes);
    }

    async _loadRenderer(revision = null, retry = false) {
        const generation = this._generation;
        const manifest = JSON.parse(this._read(this.dir.get_child('backend.json'), 4096));
        if (manifest.schema !== 1 || !/^[a-f0-9]{32}$/.test(manifest.revision))
            throw new Error('渲染模块清单不兼容');
        revision ??= manifest.revision;
        if (!/^[a-f0-9]{32}$/.test(revision) || ![manifest.revision, manifest.previous].includes(revision))
            throw new Error('渲染模块版本未安装');
        if (retry) this._modules.delete(revision);
        if (!this._modules.has(revision)) {
            const load = (async () => {
                const revisions = this.dir.get_child('revisions');
                const directory = revisions.get_child(revision);
                for (const path of [revisions, directory]) {
                    const info = path.query_info('standard::type,standard::is-symlink',
                        Gio.FileQueryInfoFlags.NOFOLLOW_SYMLINKS, null);
                    if (info.get_is_symlink() || info.get_file_type() !== Gio.FileType.DIRECTORY)
                        throw new Error('渲染模块目录不正确');
                }
                const entry = directory.get_child('renderer.js');
                this._read(entry, 262144);
                const sources = {};
                for (const name of ['contour', 'blur', 'glass'])
                    sources[name] = this._read(directory.get_child(`${name}.glsl`), 65536);
                const module = await import(entry.get_uri());
                if (module.API_VERSION !== 1 || typeof module.GlassRenderer !== 'function' ||
                    typeof module.GlassRenderer.prototype.paint !== 'function' ||
                    typeof module.GlassRenderer.prototype.update !== 'function')
                    throw new Error('渲染模块接口不兼容');
                return {revision, Renderer: module.GlassRenderer, sources};
            })();
            this._modules.set(revision, load);
        }
        const backend = await this._modules.get(revision);
        if (!this._service || generation !== this._generation)
            throw new Error('配套扩展已关闭');
        for (const key of this._modules.keys()) {
            if (![revision, this._state?.glass.revision].includes(key)) this._modules.delete(key);
        }
        return backend;
    }

    _backend() {
        if (this._desired) return Promise.resolve(this._desired);
        return this._loadRenderer().then(backend => {
            this._desired = backend;
            return backend;
        });
    }

    async _recover(revision, notice) {
        const surface = this._state?.glass;
        try {
            const manifest = JSON.parse(this._read(this.dir.get_child('backend.json'), 4096));
            if (!manifest.previous || manifest.previous === revision) return;
            const backend = await this._loadRenderer(manifest.previous);
            if (this._state?.glass !== surface || this._desired?.revision !== revision) return;
            this._desired = backend;
            surface.stage(backend.revision, backend);
            surface.notice = notice;
        } catch (error) {
            console.error(`SPW Island Glass recovery: ${error.stack ?? error}`);
        }
    }

    _reply(invocation, glass) {
        const ready = glass.ready && GLib.get_monotonic_time() - glass.lastPaint < 1_500_000;
        const detail = glass.notice || (glass.updating ? '正在热更新渲染模块，继续显示当前材质。' : '');
        const reply = ready ? detail ? `READY ${detail}` : 'READY' :
            `WAIT ${glass.failure || detail || '等待合成器首帧'}`;
        invocation.return_value(new GLib.Variant('(s)', [reply]));
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
        const present = () => this._backend().then(backend => this._present(payload, invocation, backend))
            .catch(error => {
                const state = this._state;
                if (state?.sender === sender && state.pid === payload.pid) {
                    state.updated = GLib.get_monotonic_time();
                    state.glass.notice = `热更新失败，继续使用当前材质：${error.message}`;
                    state.glass.update(payload);
                    this._reply(invocation, state.glass);
                } else {
                    invocation.return_value(new GLib.Variant('(s)', [`WAIT ${error.message}`]));
                }
            });
        if (this._state?.sender === sender && this._state.pid === payload.pid) {
            this._state.updated = GLib.get_monotonic_time();
            present();
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
                    present();
                } catch (error) {
                    invocation.return_dbus_error('org.freedesktop.DBus.Error.AccessDenied', error.message);
                }
            });
    }

    _present(payload, invocation, backend) {
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
                const glass = new GlassSurface(owner, () => this._view,
                    (revision, notice) => this._recover(revision, notice));
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
            state.glass.stage(backend.revision, backend);
            state.glass.update(payload);
            this._reply(invocation, state.glass);
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
            state.glass.clear();
            state.glass.destroy();
        }
    }

    disable() {
        this._generation++;
        this._service?.unexport();
        this._service = null;
        this._release();
        if (this._before) global.stage.disconnect(this._before);
        if (this._after) global.stage.disconnect(this._after);
        this._before = 0;
        this._after = 0;
        this._view = null;
        this._desired = null;
        this._modules?.clear();
    }
}
