// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include "GlassRenderer.h"
#include "effect/effect.h"
#include <QDBusContext>
#include <QElapsedTimer>
#include <QTimer>

namespace KWin {
class BackgroundEffectItem;

/** One geometry lease; no window rules, global blur requests or input changes. */
class SpwIslandGlassEffect final : public Effect, protected QDBusContext {
    Q_OBJECT
    Q_CLASSINFO("D-Bus Interface", "io.github.gaboron.SpwIsland.Glass")
public:
    SpwIslandGlassEffect();
    ~SpwIslandGlassEffect() override;
    bool isActive() const override;
    bool blocksDirectScanout() const override { return false; }
    int requestedEffectChainPosition() const override { return 20; }
#ifdef SPW_KWIN_67
    void prePaintScreen(ScreenPrePaintData &data) override;
#else
    void prePaintScreen(ScreenPrePaintData &data, std::chrono::milliseconds time) override;
#endif
#ifdef SPW_KWIN_68
    bool paintScreen(const RenderTarget &, const RenderViewport &, int,
                     const Region &, LogicalOutput *) override;
    bool drawWindow(const RenderTarget &, const RenderViewport &, EffectWindow *, int,
                    const Region &, WindowPaintData &) override;
#else
    void paintScreen(const RenderTarget &, const RenderViewport &, int,
                     const Region &, LogicalOutput *) override;
    void drawWindow(const RenderTarget &, const RenderViewport &, EffectWindow *, int,
                    const Region &, WindowPaintData &) override;
#endif
public Q_SLOTS:
    QString Info() const;
    QString Reload(const QString &revision);
    QString UpdateManaged(const QString &text);
    void Release();
private:
    void clearWindow();
    void paintGlass(const RenderTarget &, const RenderViewport &, EffectWindow *, int,
                    const Region &, WindowPaintData &);
    EffectWindow *m_window = nullptr;
    RenderView *m_view = nullptr;
    const Region *m_screenDamage = nullptr;
    std::unique_ptr<BackgroundEffectItem> m_background;
    std::unique_ptr<SpwIsland::GlassRenderer> m_renderer;
    std::unique_ptr<SpwIsland::GlassRenderer> m_candidate;
    SpwIsland::Contour m_contour;
    QString m_sender, m_revision, m_candidateRevision, m_notice, m_waitingReason;
    QElapsedTimer m_clock;
    QTimer m_lease;
    qint64 m_updated = 0, m_painted = -10000;
    bool m_drawingMask = false;
    bool m_service = false;
};
}
