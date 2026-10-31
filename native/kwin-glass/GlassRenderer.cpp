// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2010 Fredrik Höglund <fredrik@kde.org>
// SPDX-FileCopyrightText: 2011 Philipp Knechtges <philipp-dev@knechtges.com>
// SPDX-FileCopyrightText: 2018 Alex Nemeth <alex.nemeth329@gmail.com>
// SPDX-FileCopyrightText: 2021 Vlad Zahorodnii <vlad.zahorodnii@kde.org>
// SPDX-FileCopyrightText: 2026 GaBoron
// GPU framebuffer/repaint integration follows KDE KWin Blur and 4v3ngR/kwin-effects-glass.
// See NOTICE for the reference revisions and their GPL-2.0-or-later attribution.
#include "GlassRenderer.h"
#include "core/rendertarget.h"
#include "core/renderviewport.h"
#include "effect/effect.h"
#include "effect/effecthandler.h"
#include "effect/effectwindow.h"
#include "opengl/glframebuffer.h"
#include "opengl/glshader.h"
#include "opengl/glshadermanager.h"
#include "opengl/gltexture.h"
#include "opengl/glvertexbuffer.h"
#include <QFile>
#include <QFileInfo>
#include <array>
#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <unordered_map>

namespace SpwIsland {
using namespace KWin;
namespace {
struct Target {
    std::unique_ptr<GLTexture> texture;
    std::unique_ptr<GLFramebuffer> framebuffer;
};
Target target(const QSize &size, GLenum format) {
    if (size.isEmpty() || size.width() > 4096 || size.height() > 2048)
        throw std::runtime_error("glass texture budget exceeded");
    Target result;
    result.texture = GLTexture::allocate(format, size);
    if (!result.texture) throw std::runtime_error("cannot allocate glass texture");
    result.texture->setFilter(GL_LINEAR);
    result.texture->setWrapMode(GL_CLAMP_TO_EDGE);
    result.framebuffer = std::make_unique<GLFramebuffer>(result.texture.get());
    if (!result.framebuffer->valid()) throw std::runtime_error("cannot allocate glass framebuffer");
    return result;
}
struct FramebufferScope {
    explicit FramebufferScope(GLFramebuffer *framebuffer) { GLFramebuffer::pushFramebuffer(framebuffer); }
    ~FramebufferScope() { GLFramebuffer::popFramebuffer(); }
};
struct StateScope {
    GLboolean blend = glIsEnabled(GL_BLEND), scissor = glIsEnabled(GL_SCISSOR_TEST);
    GLint active = 0;
    std::array<GLint, 4> bindings{}, blendFactors{};
    std::array<GLint, 4> scissorBox{};
    std::array<GLfloat, 4> clearColor{};
    StateScope() {
        glGetIntegerv(GL_ACTIVE_TEXTURE, &active);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor.data());
        glGetIntegerv(GL_SCISSOR_BOX, scissorBox.data());
        for (int i = 0; i < 4; ++i) {
            glActiveTexture(GL_TEXTURE0 + i);
            glGetIntegerv(GL_TEXTURE_BINDING_2D, &bindings[i]);
        }
        glGetIntegerv(GL_BLEND_SRC_RGB, &blendFactors[0]);
        glGetIntegerv(GL_BLEND_DST_RGB, &blendFactors[1]);
        glGetIntegerv(GL_BLEND_SRC_ALPHA, &blendFactors[2]);
        glGetIntegerv(GL_BLEND_DST_ALPHA, &blendFactors[3]);
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
    }
    ~StateScope() {
        for (int i = 0; i < 4; ++i) {
            glActiveTexture(GL_TEXTURE0 + i);
            glBindTexture(GL_TEXTURE_2D, bindings[i]);
        }
        glActiveTexture(active);
        glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        glBlendFuncSeparate(blendFactors[0], blendFactors[1], blendFactors[2], blendFactors[3]);
        if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
    }
};
void bind(GLTexture *texture, int unit) {
    glActiveTexture(GL_TEXTURE0 + unit);
    texture->bind();
}
void quad(const QRectF &rect, const QRectF &uv = QRectF(0, 0, 1, 1), const Region &clip = Region::infinite()) {
    const float x0 = rect.left(), x1 = rect.right(), y0 = rect.top(), y1 = rect.bottom();
    const float u0 = uv.left(), u1 = uv.right(), v0 = 1 - uv.top(), v1 = 1 - uv.bottom();
    const std::array<GLVertex2D, 6> vertices = {{
        {{x0, y0}, {u0, v0}}, {{x1, y1}, {u1, v1}}, {{x0, y1}, {u0, v1}},
        {{x0, y0}, {u0, v0}}, {{x1, y0}, {u1, v0}}, {{x1, y1}, {u1, v1}},
    }};
    auto buffer = GLVertexBuffer::streamingBuffer();
    buffer->reset();
    buffer->setVertices(vertices);
    buffer->bindArrays();
    const bool clipped = clip != Region::infinite();
    if (clipped) glEnable(GL_SCISSOR_TEST);
    buffer->draw(clip, GL_TRIANGLES, 0, 6, clipped);
    if (clipped) glDisable(GL_SCISSOR_TEST);
    buffer->unbindArrays();
}
void pass(Target &destination, GLShader *shader, GLTexture *source) {
    FramebufferScope framebuffer(destination.framebuffer.get());
    ShaderBinder program(shader);
    bind(source, 0);
    QMatrix4x4 projection;
    projection.ortho(QRectF(QPointF(), destination.texture->size()));
    shader->setUniform("modelViewProjectionMatrix", projection);
    shader->setUniform("source", 0);
    quad(QRectF(QPointF(), destination.texture->size()));
}
std::unique_ptr<GLShader> program(const QString &directory, const QString &name) {
    const auto readShader = [&directory](const QString &name) {
        const QString path = directory + QLatin1Char('/') + name;
        const QFileInfo info(path);
        if (info.isSymLink() || !info.isFile() || info.size() > 65536)
            throw std::runtime_error("invalid shader file");
        QFile file(path);
        if (!file.open(QIODevice::ReadOnly)) throw std::runtime_error("cannot read shader file");
        const auto source = file.readAll();
        if (source.isEmpty()) throw std::runtime_error("empty shader file");
        return source;
    };
    // These are complete GLSL sources; bypass 6.6's automatic "_core" filename rewriting.
    const auto vertex = readShader(QStringLiteral("quad.vert"));
    const auto fragment = readShader(name);
    auto shader = ShaderManager::instance()->generateCustomShader(ShaderTrait::MapTexture, vertex, fragment);
#ifndef SPW_KWIN_67
    // 6.7+ returns nullptr on compilation/link failure; 6.6 may return an invalid object.
    if (shader && !shader->isValid()) shader.reset();
#endif
    if (!shader) throw std::runtime_error("KWin could not compile liquid glass shaders");
    return shader;
}
struct Frame {
    Target original, horizontal, blurred;
    std::vector<Target> pyramid;
    QRectF sample;
    double scale = 1;
};
std::shared_ptr<Frame> frame(const QSize &size, GLenum format, double scale) {
    auto result = std::make_shared<Frame>();
    result->original = target(size, format);
    result->scale = scale;
    const QSize small(std::max(1, int(std::ceil(size.width() / (4 * scale)))),
                      std::max(1, int(std::ceil(size.height() / (4 * scale)))));
    QSize stage = size;
    while (stage != small) {
        stage = QSize(std::max(small.width(), (stage.width() + 1) / 2),
                      std::max(small.height(), (stage.height() + 1) / 2));
        result->pyramid.push_back(target(stage, format));
    }
    result->horizontal = target(small, format);
    result->blurred = target(small, format);
    return result;
}
}

struct GlassRenderer::Resources {
    std::unique_ptr<GLShader> fieldProgram, blurProgram, glassProgram;
    struct View {
        std::shared_ptr<Frame> complete, spare;
        Target field, mask;
        std::vector<QVector2D> points;
        QSizeF logicalSize;
        Rect requested;
    };
    std::unordered_map<RenderView *, View> views;
    std::shared_ptr<Frame> last;
};

GlassRenderer::GlassRenderer(const QString &directory) : m_resources(std::make_unique<Resources>()) {
    m_resources->fieldProgram = program(directory, QStringLiteral("contour.frag"));
    m_resources->blurProgram = program(directory, QStringLiteral("blur.frag"));
    m_resources->glassProgram = program(directory, QStringLiteral("glass.frag"));
}
GlassRenderer::~GlassRenderer() = default;
void GlassRenderer::removeView(RenderView *view) { m_resources->views.erase(view); }
void GlassRenderer::clearFrames() { m_resources->views.clear(); m_resources->last.reset(); }

bool GlassRenderer::paint(const RenderTarget &renderTarget, const RenderViewport &viewport,
    RenderView *view, EffectWindow *window, int mask, const Region &damage,
    WindowPaintData &data, const Contour &contour) {
    if (!view || contour.points.empty()) return false;
    // Desktop overview / third-party window transformations keep their normal compositor path.
    if (effects->activeFullScreenEffect() || data.xScale() != 1 || data.yScale() != 1 ||
        data.xTranslation() != 0 || data.yTranslation() != 0) return false;
    const auto geometry = window->frameGeometry();
    const double scale = viewport.scale();
    const Rect client = geometry.rounded();
    const Rect sample = client.adjusted(-padding, -padding, padding, padding)
                            .intersected(viewport.mapFromDeviceCoordinatesContained(viewport.deviceRect()));
    if (sample.isEmpty()) return false;
    StateScope state;
    auto &cache = m_resources->views[view];
    const Region fresh = viewport.mapFromDeviceCoordinatesContained(damage);
    std::shared_ptr<Frame> copy = cache.complete ? cache.complete : m_resources->last;
    if ((Region(sample) - fresh).isEmpty()) {
        const QSize size(std::ceil(sample.width() * scale), std::ceil(sample.height() * scale));
        const GLenum format = renderTarget.texture() ? renderTarget.texture()->internalFormat() : GL_RGBA8;
        auto next = cache.spare;
        if (!next || next->original.texture->size() != size || next->scale != scale ||
            next->original.texture->internalFormat() != format) next = frame(size, format, scale);
#ifdef SPW_KWIN_68
        if (!next->original.framebuffer->blitFromRenderTarget(renderTarget, viewport, sample, Rect(QPoint(), size)))
            throw std::runtime_error("KWin backdrop blit failed");
#else
        next->original.framebuffer->blitFromRenderTarget(renderTarget, viewport, sample, Rect(QPoint(), size));
#endif
        next->sample = QRectF(sample.x() - geometry.x(), sample.y() - geometry.y(), sample.width(), sample.height());
        cache.spare = cache.complete;
        cache.complete = copy = next;
        cache.requested = Rect();
    } else if (cache.requested != sample) {
        effects->addRepaint(RectF(sample));
        cache.requested = sample;
    }
    if (!copy) return false;
    m_resources->last = copy;
    const QSize clientSize(std::ceil(geometry.width() * scale), std::ceil(geometry.height() * scale));
    if (!cache.mask.texture || cache.mask.texture->size() != clientSize) {
        auto mask = target(clientSize, GL_RGBA8);
        auto field = target(clientSize, GL_RGBA16F);
        cache.mask = std::move(mask);
        cache.field = std::move(field);
        cache.points.clear();
    }
    // Render this application's alpha inside KWin; no desktop pixels are read back to the client.
    {
        FramebufferScope framebuffer(cache.mask.framebuffer.get());
        RenderTarget target(cache.mask.framebuffer.get());
        RenderViewport maskViewport(geometry, scale, target, QPoint());
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        WindowPaintData neutral;
        neutral.setOpacity(1);
#ifdef SPW_KWIN_68
        if (!effects->drawWindow(target, maskViewport, window, Effect::PAINT_WINDOW_TRANSFORMED |
                                Effect::PAINT_WINDOW_TRANSLUCENT, Region::infinite(), neutral)) return false;
#else
        effects->drawWindow(target, maskViewport, window, Effect::PAINT_WINDOW_TRANSFORMED |
                            Effect::PAINT_WINDOW_TRANSLUCENT, Region::infinite(), neutral);
#endif
    }
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    auto field = m_resources->fieldProgram.get();
    if (cache.points != contour.points || cache.logicalSize != QSizeF(geometry.width(), geometry.height())) {
        ShaderBinder binder(field);
        std::vector<float> points;
        for (const auto &point : contour.points) {
            points.push_back(point.x() * geometry.width());
            points.push_back(point.y() * geometry.height());
        }
        glUniform2fv(field->uniformLocation("points"), contour.points.size(), points.data());
        field->setUniform("pointCount", int(contour.points.size()));
        field->setUniform("clientSize", QVector2D(geometry.width(), geometry.height()));
        pass(cache.field, field, cache.mask.texture.get());
        cache.points = contour.points;
        cache.logicalSize = QSizeF(geometry.width(), geometry.height());
    }
    auto blur = m_resources->blurProgram.get();
    {
        ShaderBinder binder(blur);
        GLTexture *filtered = copy->original.texture.get();
        blur->setUniform("prefilterMode", 1);
        for (auto &stage : copy->pyramid) {
            blur->setUniform("stepSize", QVector2D(stage.texture->width() < filtered->width() ? 1.0 / filtered->width() : 0,
                stage.texture->height() < filtered->height() ? 1.0 / filtered->height() : 0));
            pass(stage, blur, filtered);
            filtered = stage.texture.get();
        }
        blur->setUniform("prefilterMode", 0);
        blur->setUniform("stepSize", QVector2D(1.0 / filtered->width(), 0));
        pass(copy->horizontal, blur, filtered);
        blur->setUniform("stepSize", QVector2D(0, 1.0 / copy->horizontal.texture->height()));
        pass(copy->blurred, blur, copy->horizontal.texture.get());
    }
    auto glass = m_resources->glassProgram.get();
    ShaderBinder binder(glass);
    bind(copy->original.texture.get(), 0);
    bind(copy->blurred.texture.get(), 1);
    bind(cache.field.texture.get(), 2);
    bind(cache.mask.texture.get(), 3);
    glass->setUniform("background", 0);
    glass->setUniform("blurred", 1);
    glass->setUniform("contourMap", 2);
    glass->setUniform("clientMask", 3);
    glass->setUniform("clientSize", QVector2D(geometry.width(), geometry.height()));
    glass->setUniform("sampleRect", QVector4D(copy->sample.x(), copy->sample.y(), copy->sample.width(), copy->sample.height()));
    glass->setUniform("maskAlpha", contour.maskAlpha * contour.opacity);
    glass->setUniform("revealOpacity", contour.opacity * window->opacity() * data.opacity());
    float left = geometry.width(), right = 0, top = geometry.height(), bottom = 0;
    for (const auto &p : contour.points) {
        left = std::min(left, float(p.x() * geometry.width())); right = std::max(right, float(p.x() * geometry.width()));
        top = std::min(top, float(p.y() * geometry.height())); bottom = std::max(bottom, float(p.y() * geometry.height()));
    }
    glass->setUniform("collapseScale", std::clamp(std::min(right - left, bottom - top) / 58.f, .0001f, 1.f));
    glass->setUniform("modelViewProjectionMatrix", viewport.projectionMatrix());
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    const Region clip = damage == Region::infinite() ? damage :
        viewport.transform().map(damage, renderTarget.transformedSize());
    quad(QRectF(geometry.x() * scale, geometry.y() * scale, geometry.width() * scale, geometry.height() * scale),
         QRectF(-copy->sample.x() / copy->sample.width(), -copy->sample.y() / copy->sample.height(),
                geometry.width() / copy->sample.width(), geometry.height() / copy->sample.height()), clip);
    return true;
}
}
