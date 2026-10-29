// SPDX-License-Identifier: GPL-3.0-only
import Clutter from 'gi://Clutter';
import GObject from 'gi://GObject';
import Graphene from 'gi://Graphene';

/** Stable native actor; renderer revisions contain no GObject type registrations. */
export const GlassSurface = GObject.registerClass(class SpwIslandGlassSurface extends Clutter.Actor {
    _init(owner, currentView, recover) {
        super._init({reactive: false});
        this._owner = owner;
        this._currentView = currentView;
        this._recover = recover;
        this._active = null;
        this._candidate = null;
        this._revision = null;
        this._targetRevision = null;
        this._payload = null;
        this._failedRevision = null;
        this.notice = '';
    }

    get ready() { return this._active?.ready ?? false; }
    get failure() { return this._active?.failure ?? this._candidate?.failure ?? ''; }
    get lastPaint() { return this._active?.lastPaint ?? 0; }
    get revision() { return this._revision; }
    get updating() { return this._candidate !== null; }

    stage(revision, backend, retry = false) {
        if (revision === this._revision || revision === this._targetRevision ||
            (!retry && revision === this._failedRevision))
            return;
        let next;
        try {
            next = new backend.Renderer(this._owner, this, backend.sources, this._currentView);
            if (this._payload) next.update(this._payload);
        } catch (error) {
            this._reject(revision, error.message);
            return;
        }
        this._candidate = next;
        this._targetRevision = revision;
        this._failedRevision = null;
        this.notice = '';
        this.queue_redraw();
    }

    update(payload) {
        this._payload = payload;
        const [width, height] = this._owner.get_size();
        if (this.width !== width || this.height !== height)
            this.set_size(width, height);
        this._active?.update(payload);
        this._candidate?.update(payload);
        this.queue_redraw();
    }

    vfunc_get_paint_volume(volume) {
        if (!volume.set_from_allocation(this)) return false;
        const padding = Math.min(512, Math.max(120, this._active?.padding ?? 0, this._candidate?.padding ?? 0));
        volume.set_origin(new Graphene.Point3D({x: -padding, y: -padding, z: 0}));
        volume.set_width(this.width + padding * 2);
        volume.set_height(this.height + padding * 2);
        return true;
    }

    vfunc_paint_node(root, paintContext) {
        const next = this._candidate;
        if (next) {
            // The candidate paints before the old composite, so its backdrop cannot contain old glass.
            if (this._paint(next, root, paintContext)) {
                this._active = next;
                this._revision = this._targetRevision;
                this._candidate = null;
                this._targetRevision = null;
                this.notice = '';
                return;
            }
            if (next.failure) {
                this._reject(this._targetRevision, next.failure);
                this._candidate = null;
                this._targetRevision = null;
            }
        }
        if (this._active) this._paint(this._active, root, paintContext);
    }

    _paint(engine, root, paintContext) {
        try {
            return engine.paint(root, paintContext);
        } catch (error) {
            engine.failure = error.message;
            console.error(`SPW Island Glass: ${error.stack ?? error}`);
            return false;
        }
    }

    _reject(revision, message) {
        this.notice = `热更新失败，继续使用当前材质：${message}`;
        this._failedRevision = revision;
        if (!this._active) this._recover?.(revision, this.notice);
    }

    clear() {
        this._active = null;
        this._candidate = null;
        this._payload = null;
        this._revision = null;
        this._targetRevision = null;
        this._failedRevision = null;
        this._recover = null;
    }
});
