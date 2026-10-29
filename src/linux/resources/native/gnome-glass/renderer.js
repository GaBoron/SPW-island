// SPDX-License-Identifier: GPL-3.0-only
import Clutter from 'gi://Clutter';
import Cogl from 'gi://Cogl';
import GLib from 'gi://GLib';
import Mtk from 'gi://Mtk';

const SAMPLE_PADDING = 120;
const BLUR_TEXEL_DIP = 4;
export const API_VERSION = 1;

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

/** Replaceable GPU engine; the stable Shell actor owns layout and lifecycle. */
export class GlassRenderer {
    constructor(owner, actor, sources, currentView) {
        this._actor = actor;
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

    get width() { return this._actor.width; }
    get height() { return this._actor.height; }
    get padding() { return SAMPLE_PADDING; }

    update(payload) {
        this._payload = payload;
    }

    _prepareField(root) {
        const payload = this._payload;
        const signature = JSON.stringify([payload.points, this.width, this.height, this._actor.get_resource_scale()]);
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
        const scale = this._actor.get_resource_scale();
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
        const [x, y] = this._actor.get_transformed_position();
        const [width, height] = this._actor.get_transformed_size();
        let cache = this._views.get(view);
        if (!cache) {
            cache = {complete: null, spare: null, requested: null};
            this._views.set(view, cache);
        }
        // Completed frames stay immutable while a new position or monitor is being prepared.
        const retained = () => cache.complete ?? this._lastCopy ?? null;
        if (this._actor.is_in_clone_paint() || paintContext.get_framebuffer() !== view.get_framebuffer() ||
            Math.abs(width - this.width) > .1 || Math.abs(height - this.height) > .1)
            return retained();
        const layout = view.layout;
        const scale = view.get_scale();
        const source = view.get_framebuffer();
        const x0 = Math.max(0, Math.floor((x - SAMPLE_PADDING - layout.x) * scale));
        const y0 = Math.max(0, Math.floor((y - SAMPLE_PADDING - layout.y) * scale));
        const x1 = Math.min(source.get_width(), Math.ceil((x + this.width + SAMPLE_PADDING - layout.x) * scale));
        const y1 = Math.min(source.get_height(), Math.ceil((y + this.height + SAMPLE_PADDING - layout.y) * scale));
        if (x1 <= x0 || y1 <= y0)
            return retained();
        const stageRect = new Mtk.Rectangle({
            x: Math.floor(layout.x + x0 / scale), y: Math.floor(layout.y + y0 / scale),
            width: Math.ceil(layout.x + x1 / scale) - Math.floor(layout.x + x0 / scale),
            height: Math.ceil(layout.y + y1 / scale) - Math.floor(layout.y + y0 / scale),
        });
        const origin = JSON.stringify([stageRect.x, stageRect.y, stageRect.width, stageRect.height]);
        const clip = paintContext.get_redraw_clip();
        // Never read stale foreground pixels, or discard a completed image for an incomplete one.
        if (clip && clip.contains_rectangle(stageRect) !== Mtk.RegionOverlap.IN) {
            if (cache.requested !== origin) {
                // A bounded motion margin also covers the next small drag step.
                const left = Math.max(layout.x, stageRect.x - 64);
                const top = Math.max(layout.y, stageRect.y - 64);
                const right = Math.min(layout.x + layout.width, stageRect.x + stageRect.width + 64);
                const bottom = Math.min(layout.y + layout.height, stageRect.y + stageRect.height + 64);
                global.stage.queue_redraw_with_clip(new Mtk.Rectangle({x: left, y: top,
                    width: right - left, height: bottom - top}));
                cache.requested = origin;
            }
            return retained();
        }
        cache.requested = null;
        const format = source instanceof Cogl.Offscreen ? source.get_texture().get_format() : null;
        let copy = cache.spare;
        if (!copy || copy.width !== x1 - x0 || copy.height !== y1 - y0 ||
            copy.format !== format || copy.blurScale !== scale) {
            if (x1 - x0 > 4096 || y1 - y0 > 2048)
                throw new Error('词岛后景超出合成器纹理预算');
            copy = target(x1 - x0, y1 - y0, format);
            Object.assign(copy, blurResources(copy.width, copy.height, scale, format, this._sources.blur));
            copy.glassProgram = program(this._sources.glass);
        }
        const copyRoot = Clutter.RootNode.new(copy.framebuffer, view.color_state, new Cogl.Color(), 0);
        const blit = Clutter.BlitNode.new(source);
        blit.add_blit_rectangle(x0, y0, 0, 0, x1 - x0, y1 - y0);
        copyRoot.add_child(blit);
        root.add_child(copyRoot);
        copy.sample = [layout.x + x0 / scale - x, layout.y + y0 / scale - y,
            (x1 - x0) / scale, (y1 - y0) / scale];
        // Publish only after all fresh pixels have been queued into a separate target.
        cache.spare = cache.complete;
        cache.complete = copy;
        return copy;
    }

    paint(root, paintContext) {
        if (!this._payload || this.failure)
            return false;
        try {
            this._prepareField(root);
            const view = this._currentView();
            const copy = view ? this._copy(root, paintContext, view) : this._lastCopy;
            if (!copy) return false;
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
            scalar(glass, 'revealOpacity', this._payload.opacity * this._actor.get_paint_opacity() / 255);
            scalar(glass, 'collapseScale', Math.min(1, Math.min(this._fieldRect[2], this._fieldRect[3]) / 58));
            const [fx, fy, fw, fh] = this._fieldRect;
            const [sx, sy, sw, sh] = copy.sample;
            rectangle(root, glass, this._fieldRect, [(fx - sx) / sw, (fy - sy) / sh,
                (fx + fw - sx) / sw, (fy + fh - sy) / sh]);
            this.ready = true;
            this.lastPaint = GLib.get_monotonic_time();
            // Release per-view allocations after a window leaves a monitor.
            const views = this._actor.peek_stage_views();
            for (const old of this._views.keys()) {
                if (!views.includes(old)) this._views.delete(old);
            }
            return true;
        } catch (error) {
            this.failure = error.message;
            this.ready = false;
            console.error(`SPW Island Glass: ${error.stack ?? error}`);
            return false;
        }
    }
}
