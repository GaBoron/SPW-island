// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include <QString>
#include <QVector2D>
#include <memory>
#include <vector>

namespace KWin {
class EffectWindow;
class RenderTarget;
class RenderViewport;
class RenderView;
class Region;
class WindowPaintData;
}

namespace SpwIsland {
struct Contour {
    std::vector<QVector2D> points;
    double maskAlpha = 0;
    double opacity = 0;
};

/** GPU-only backdrop, alpha-mask and optical resources for one renderer revision. */
class GlassRenderer {
public:
    explicit GlassRenderer(const QString &directory);
    ~GlassRenderer();
    bool paint(const KWin::RenderTarget &target, const KWin::RenderViewport &viewport,
               KWin::RenderView *view, KWin::EffectWindow *window, int mask,
               const KWin::Region &damage, KWin::WindowPaintData &data, const Contour &contour);
    void removeView(KWin::RenderView *view);
    void clearFrames();
    static constexpr int padding = 120;
private:
    struct Resources;
    std::unique_ptr<Resources> m_resources;
};
}
