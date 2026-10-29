// SPDX-License-Identifier: GPL-3.0-only
import Clutter from 'gi://Clutter';
import Cogl from 'gi://Cogl';
import GObject from 'gi://GObject';
import GLib from 'gi://GLib';
import Graphene from 'gi://Graphene';
import Mtk from 'gi://Mtk';

const SAMPLE_PADDING = 120;
const BLUR_TEXEL_DIP = 4;

function context() {
    return global.stage.context.get_backend().get_cogl_context();
}

function target(width, height, format = null) {
    const texture = format === null
        ? Cogl.Texture2D.new_with_size(context(), width, height)
        : Cogl.Texture2D.new_with_format(context(), width, height, format);
    const framebuffer = Cogl.Offscreen.new_with_texture(texture);
    framebuffer.allocate();
    return {texture, framebuffer, width, height, format};
}

function sampler(pipeline, layer, texture) {
    pipeline.set_layer_texture(layer, texture);
    pipeline.set_layer_wrap_mode(layer, Cogl.PipelineWrapMode.CLAMP_TO_EDGE);
    pipeline.set_layer_filters(layer, Cogl.PipelineFilter.LINEAR, Cogl.PipelineFilter.LINEAR);
}

function vector(pipeline, name, values) {
    pipeline.set_uniform_float(pipeline.get_uniform_location(name), values.length, 1, values);
}

function scalar(pipeline, name, value) {
    pipeline.set_uniform_1f(pipeline.get_uniform_location(name), value);
}

function program(source) {
    const start = source.indexOf('void main()');
    const body = source.slice(source.indexOf('{', start) + 1, source.lastIndexOf('}'));
    const snippet = Cogl.Snippet.new(Cogl.SnippetHook.FRAGMENT, source.slice(0, start), null);
    snippet.set_replace(body);
    const pipeline = Cogl.Pipeline.new(context());
    pipeline.add_snippet(snippet);
    pipeline.set_blend('RGBA = ADD(SRC_COLOR, 0)');
    return pipeline;
}

function rectangle(node, pipeline, rect, uv = [0, 0, 1, 1]) {
    const draw = Clutter.PipelineNode.new(pipeline);
    draw.add_texture_rectangle(new Clutter.ActorBox({x1: rect[0], y1: rect[1],
        x2: rect[0] + rect[2], y2: rect[1] + rect[3]}), ...uv);
    node.add_child(draw);
}

function pass(root, destination, pipeline) {
    destination.framebuffer.orthographic(0, 0, destination.width, destination.height, -1, 1);
    const layer = Clutter.LayerNode.new_to_framebuffer(destination.framebuffer, pipeline);
    root.add_child(layer);
    rectangle(layer, pipeline, [0, 0, destination.width, destination.height]);
}

function blurResources(width, height, scale, format, source) {
    const factor = Math.max(1, BLUR_TEXEL_DIP * scale);
    const blurWidth = Math.max(1, Math.ceil(width / factor));
    const blurHeight = Math.max(1, Math.ceil(height / factor));
    const downsample = [];
    let stageWidth = width;
    let stageHeight = height;
    // Limit each reduction to 2x so the prefilter covers the source without gaps.
    while (stageWidth > blurWidth || stageHeight > blurHeight) {
        stageWidth = Math.max(blurWidth, Math.ceil(stageWidth / 2));
        stageHeight = Math.max(blurHeight, Math.ceil(stageHeight / 2));
        const stage = target(stageWidth, stageHeight, format);
        stage.pipeline = program(source);
        downsample.push(stage);
    }
    return {blurScale: scale, downsample,
        horizontal: target(blurWidth, blurHeight, format),
        blurred: target(blurWidth, blurHeight, format),
        horizontalProgram: program(source), verticalProgram: program(source)};
}

/** A non-interactive child below this one window's surface; all images stay in Cogl. */
export const GlassActor = GObject.registerClass(class SpwIslandGlassActor extends Clutter.Actor {
    _init(owner, sources, currentView) {
        super._init({reactive: false});
        this._owner = owner;
        this._sources = sources;
        this._currentView = currentView;
        this._views = new Map();
        this._field = null;
        this._geometry = null;
        this._payload = null;
        this.ready = false;
        this.failure = '';
    }

    update(payload) {
        this._payload = payload;
        const [width, height] = this._owner.get_size();
        if (this.width !== width || this.height !== height)
            this.set_size(width, height);
        this.queue_redraw();
    }

    vfunc_get_paint_volume(volume) {
        if (!volume.set_from_allocation(this))
            return false;
        // Refraction and blur sample outside the silhouette. Damage must include those pixels.
        volume.set_origin(new Graphene.Point3D({x: -SAMPLE_PADDING, y: -SAMPLE_PADDING, z: 0}));
        volume.set_width(this.width + SAMPLE_PADDING * 2);
        volume.set_height(this.height + SAMPLE_PADDING * 2);
        return true;
    }

    _prepareField(root) {
        const payload = this._payload;
        const signature = JSON.stringify([payload.points, this.width, this.height, this.get_resource_scale()]);
        if (signature === this._geometry)
            return;
        const points = payload.points.map(([x, y]) => [x * this.width, y * this.height]);
        const xs = points.map(p => p[0]);
        const ys = points.map(p => p[1]);
        const left = Math.floor(Math.min(...xs)) - 1;
        const top = Math.floor(Math.min(...ys)) - 1;
        const right = Math.ceil(Math.max(...xs)) + 1;
        const bottom = Math.ceil(Math.max(...ys)) + 1;
        const rect = [left, top, right - left, bottom - top];
        const scale = this.get_resource_scale();
        const width = Math.max(1, Math.ceil(rect[2] * scale));
        const height = Math.max(1, Math.ceil(rect[3] * scale));
        if (width > 4096 || height > 2048)
            throw new Error('词岛轮廓超出合成器纹理预算');
        const field = this._field?.width === width && this._field?.height === height
            ? this._field : target(width, height);
        const pipeline = this._contourProgram ??= program(this._sources.contour);
        this._dummy ??= target(1, 1);
        sampler(pipeline, 0, this._dummy.texture);
        vector(pipeline, 'fieldRect', rect);
        pipeline.set_uniform_1i(pipeline.get_uniform_location('pointCount'), points.length);
        const values = points.flat();
        while (values.length < 512) values.push(0);
        pipeline.set_uniform_float(pipeline.get_uniform_location('points'), 2, 256, values);
        pass(root, field, pipeline);
        this._field = field;
        this._fieldRect = rect;
        this._geometry = signature;
    }

    _copy(root, paintContext, view) {
        const [x, y] = this.get_transformed_position();
        const [width, height] = this.get_transformed_size();
        const cached = this._views.get(view);
        // During overview/ancestor offscreen rendering, the live framebuffer is not the backdrop.
        if (this.is_in_clone_paint() || paintContext.get_framebuffer() !== view.get_framebuffer() ||
            Math.abs(width - this.width) > .1 || Math.abs(height - this.height) > .1)
            return cached?.initialized ? cached : this._lastCopy ?? null;
        // Follow the existing stable canvas rather than reallocating for every lyric morph.
        const rect = [0, 0, this.width, this.height];
        const padding = SAMPLE_PADDING;
        const layout = view.layout;
        const scale = view.get_scale();
        const source = view.get_framebuffer();
        const x0 = Math.max(0, Math.floor((x + rect[0] - padding - layout.x) * scale));
        const y0 = Math.max(0, Math.floor((y + rect[1] - padding - layout.y) * scale));
        const x1 = Math.min(source.get_width(), Math.ceil((x + rect[0] + rect[2] + padding - layout.x) * scale));
        const y1 = Math.min(source.get_height(), Math.ceil((y + rect[1] + rect[3] + padding - layout.y) * scale));
        if (x1 <= x0 || y1 <= y0)
            return null;
        const sample = [layout.x + x0 / scale - x, layout.y + y0 / scale - y,
            (x1 - x0) / scale, (y1 - y0) / scale];
        const clip = paintContext.get_redraw_clip();
        // Match HDR/rotated-view formats, as a GPU blit requires matching storage classes.
        const format = source instanceof Cogl.Offscreen ? source.get_texture().get_format() : null;
        let copy = this._views.get(view);
        let previous = copy;
        if (!copy || copy.width !== x1 - x0 || copy.height !== y1 - y0 ||
            copy.format !== format || copy.blurScale !== scale) {
            if (x1 - x0 > 4096 || y1 - y0 > 2048)
                throw new Error('词岛后景超出合成器纹理预算');
            copy = target(x1 - x0, y1 - y0, format);
            Object.assign(copy, blurResources(copy.width, copy.height, scale, format, this._sources.blur));
            copy.glassProgram = program(this._sources.glass);
            this._views.set(view, copy);
        }
        const stageRect = new Mtk.Rectangle({
            x: Math.floor(layout.x + x0 / scale), y: Math.floor(layout.y + y0 / scale),
            width: Math.ceil(layout.x + x1 / scale) - Math.floor(layout.x + x0 / scale),
            height: Math.ceil(layout.y + y1 / scale) - Math.floor(layout.y + y0 / scale),
        });
        const origin = JSON.stringify([stageRect.x, stageRect.y, stageRect.width, stageRect.height]);
        if (copy.origin !== origin) {
            if (copy === previous) {
                // Shift retained pixels through a second GPU target; overlapping in-place blits are undefined.
                copy.spare ??= target(copy.width, copy.height, format);
                previous = {framebuffer: copy.framebuffer, texture: copy.texture, width: copy.width,
                    height: copy.height, x0: copy.x0, y0: copy.y0, valid: copy.valid,
                    coordinates: copy.coordinates, format: copy.format};
                const replacement = copy.spare;
                copy.spare = {framebuffer: copy.framebuffer, texture: copy.texture};
                copy.framebuffer = replacement.framebuffer;
                copy.texture = replacement.texture;
            }
            const coordinates = JSON.stringify([layout.x, layout.y, scale]);
            const compatible = previous?.valid && previous.format === format && previous.coordinates === coordinates;
            copy.valid = compatible ? previous.valid.copy() : Mtk.Region.create();
            copy.valid.intersect_rectangle(stageRect);
            if (compatible) {
                const left = Math.max(x0, previous.x0);
                const top = Math.max(y0, previous.y0);
                const right = Math.min(x1, previous.x0 + previous.width);
                const bottom = Math.min(y1, previous.y0 + previous.height);
                if (right > left && bottom > top) {
                    const shiftRoot = Clutter.RootNode.new(copy.framebuffer, view.color_state, new Cogl.Color(), 0);
                    const shift = Clutter.BlitNode.new(previous.framebuffer);
                    shift.add_blit_rectangle(left - previous.x0, top - previous.y0, left - x0, top - y0,
                        right - left, bottom - top);
                    shiftRoot.add_child(shift);
                    root.add_child(shiftRoot);
                }
            }
            copy.coordinates = coordinates;
            copy.origin = origin;
            copy.initialized = false;
        }
        const damage = clip ? clip.copy() : Mtk.Region.create_rectangle(stageRect);
        damage.intersect_rectangle(stageRect);
        // Mtk's rectangle getter returns a struct by value, which is unsafe through this GI binding.
        // Only copy an entirely fresh sampling area; retain the GPU cache during partial damage.
        if (damage.contains_rectangle(stageRect) === Mtk.RegionOverlap.IN) {
            const copyRoot = Clutter.RootNode.new(copy.framebuffer, view.color_state, new Cogl.Color(), 0);
            const blit = Clutter.BlitNode.new(source);
            blit.add_blit_rectangle(x0, y0, 0, 0, x1 - x0, y1 - y0);
            copyRoot.add_child(blit);
            root.add_child(copyRoot);
            copy.valid.union_rectangle(stageRect);
            copy.requested = null;
        } else if (copy.requested !== origin) {
            // One bounded repaint refreshes the sample without copying stale foreground pixels.
            global.stage.queue_redraw_with_clip(stageRect);
            copy.requested = origin;
        }
        copy.sample = sample;
        copy.x = x;
        copy.y = y;
        copy.x0 = x0;
        copy.y0 = y0;
        const field = this._fieldRect;
        const neededLeft = Math.max(stageRect.x, Math.floor(x + field[0] - 88));
        const neededTop = Math.max(stageRect.y, Math.floor(y + field[1] - 88));
        const neededRight = Math.min(stageRect.x + stageRect.width, Math.ceil(x + field[0] + field[2] + 88));
        const neededBottom = Math.min(stageRect.y + stageRect.height, Math.ceil(y + field[1] + field[3] + 88));
        const needed = new Mtk.Rectangle({x: neededLeft, y: neededTop,
            width: Math.max(0, neededRight - neededLeft), height: Math.max(0, neededBottom - neededTop)});
        if (copy.valid.contains_rectangle(needed) !== Mtk.RegionOverlap.IN)
            return null;
        copy.initialized = true;
        return copy;
    }

    vfunc_paint_node(root, paintContext) {
        if (!this._payload || this.failure)
            return;
        try {
            this._prepareField(root);
            const view = this._currentView();
            const copy = view ? this._copy(root, paintContext, view) : this._lastCopy;
            if (!copy) return;
            this._lastCopy = copy;
            let filtered = copy;
            for (const stage of copy.downsample) {
                sampler(stage.pipeline, 0, filtered.texture);
                stage.pipeline.set_uniform_1i(stage.pipeline.get_uniform_location('prefilterMode'), 1);
                vector(stage.pipeline, 'stepSize', [stage.width < filtered.width ? 1 / filtered.width : 0,
                    stage.height < filtered.height ? 1 / filtered.height : 0]);
                pass(root, stage, stage.pipeline);
                filtered = stage;
            }
            // Unit-texel Gaussian taps on the filtered image give a continuous ~8 DIP blur.
            const horizontal = copy.horizontalProgram;
            sampler(horizontal, 0, filtered.texture);
            horizontal.set_uniform_1i(horizontal.get_uniform_location('prefilterMode'), 0);
            vector(horizontal, 'stepSize', [1 / filtered.width, 0]);
            pass(root, copy.horizontal, horizontal);
            const vertical = copy.verticalProgram;
            sampler(vertical, 0, copy.horizontal.texture);
            vertical.set_uniform_1i(vertical.get_uniform_location('prefilterMode'), 0);
            vector(vertical, 'stepSize', [0, 1 / copy.horizontal.height]);
            pass(root, copy.blurred, vertical);
            const glass = copy.glassProgram;
            sampler(glass, 0, copy.texture);
            sampler(glass, 1, copy.blurred.texture);
            sampler(glass, 2, this._field.texture);
            const foreground = this._owner.get_texture()?.get_texture();
            if (!foreground?.is_simple())
                throw new Error('词岛表面无法提供 GPU 轮廓遮罩');
            sampler(glass, 3, foreground.get_plane(0));
            glass.set_blend('RGBA = ADD(SRC_COLOR, DST_COLOR * (1 - SRC_COLOR[A]))');
            vector(glass, 'fieldRect', this._fieldRect);
            vector(glass, 'sampleRect', copy.sample);
            vector(glass, 'clientSize', [this.width, this.height]);
            scalar(glass, 'maskAlpha', this._payload.maskAlpha * this._payload.opacity);
            scalar(glass, 'revealOpacity', this._payload.opacity * this.get_paint_opacity() / 255);
            scalar(glass, 'collapseScale', Math.min(1, Math.min(this._fieldRect[2], this._fieldRect[3]) / 58));
            const [fx, fy, fw, fh] = this._fieldRect;
            const [sx, sy, sw, sh] = copy.sample;
            rectangle(root, glass, this._fieldRect, [(fx - sx) / sw, (fy - sy) / sh,
                (fx + fw - sx) / sw, (fy + fh - sy) / sh]);
            this.ready = true;
            this.lastPaint = GLib.get_monotonic_time();
            // Release per-view allocations after a window leaves a monitor.
            const views = this.peek_stage_views();
            for (const old of this._views.keys()) {
                if (!views.includes(old)) this._views.delete(old);
            }
        } catch (error) {
            this.failure = error.message;
            this.ready = false;
            console.error(`SPW Island Glass: ${error.stack ?? error}`);
        }
    }
});
