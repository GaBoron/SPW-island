// SPDX-License-Identifier: GPL-3.0-only
#include "IslandGlassEffect.h"
#include "effect/effecthandler.h"
#include "scene/backgroundeffectitem.h"
#include "scene/windowitem.h"
#include <QDBusConnection>
#include <QDBusConnectionInterface>
#include <QDBusMessage>
#include <QDBusReply>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QStandardPaths>
#include <cmath>
#include <stdexcept>

namespace {
const QString objectPath = QStringLiteral("/io/github/gaboron/SpwIsland/KWinGlass");
bool revisionName(const QString &name) {
    return QRegularExpression(QStringLiteral("^[a-f0-9]{32}$")).match(name).hasMatch();
}
QString rendererDirectory(const QString &revision) {
    if (!revisionName(revision)) throw std::runtime_error("invalid renderer revision");
    QString path = QStandardPaths::writableLocation(QStandardPaths::GenericDataLocation);
    for (const auto &part : {QStringLiteral("spw-island-support"), QStringLiteral("kwin-glass"),
                             QStringLiteral("revisions"), revision}) {
        path += QLatin1Char('/') + part;
        const QFileInfo info(path);
        if (info.isSymLink() || !info.isDir()) throw std::runtime_error("invalid renderer directory");
    }
    return path;
}
}

namespace KWin {
SpwIslandGlassEffect::SpwIslandGlassEffect() {
    m_clock.start();
    m_service = QDBusConnection::sessionBus().registerObject(objectPath, this, QDBusConnection::ExportAllSlots);
    m_lease.setInterval(1000);
    connect(&m_lease, &QTimer::timeout, this, [this] {
        if (m_clock.elapsed() - m_updated > 2500) clearWindow();
    });
    connect(effects, &EffectsHandler::windowDeleted, this, [this](EffectWindow *window) {
        if (window == m_window) {
            m_window = nullptr;
            clearWindow();
        }
    });
    connect(effects, &EffectsHandler::viewRemoved, this, [this](RenderView *view) {
        effects->makeOpenGLContextCurrent();
        if (m_renderer) m_renderer->removeView(view);
        if (m_candidate) m_candidate->removeView(view);
        if (m_view == view) m_view = nullptr;
    });
}

SpwIslandGlassEffect::~SpwIslandGlassEffect() {
    if (m_service) QDBusConnection::sessionBus().unregisterObject(objectPath);
    clearWindow();
    effects->makeOpenGLContextCurrent();
    m_candidate.reset();
    m_renderer.reset();
}

bool SpwIslandGlassEffect::isActive() const {
    return m_service && m_window && (m_renderer || m_candidate);
}

QString SpwIslandGlassEffect::Info() const {
    return QString::fromUtf8(QJsonDocument(QJsonObject{
        {"protocol", 1}, {"native", QStringLiteral(SPW_NATIVE_REVISION)},
        {"kwin", QStringLiteral(SPW_KWIN_VERSION)}, {"revision", m_revision},
        {"plugin", QStringLiteral(SPW_PLUGIN_NAME)}
    }).toJson(QJsonDocument::Compact));
}

QString SpwIslandGlassEffect::Reload(const QString &revision) {
    try {
        if (revision == m_revision || revision == m_candidateRevision) return QStringLiteral("LOADED");
        effects->makeOpenGLContextCurrent();
        auto candidate = std::make_unique<SpwIsland::GlassRenderer>(rendererDirectory(revision));
        m_candidate = std::move(candidate);
        m_candidateRevision = revision;
        m_notice.clear();
        if (m_window) m_window->addRepaintFull();
        return QStringLiteral("LOADED");
    } catch (const std::exception &error) {
        m_notice = QStringLiteral("KWin 热更新失败：") + QString::fromUtf8(error.what());
        return QStringLiteral("WAIT ") + m_notice;
    }
}

QString SpwIslandGlassEffect::UpdateManaged(const QString &text) {
    if (!m_service || text.size() > 32000) return QStringLiteral("WAIT 轮廓数据过大或服务不可用");
    const auto document = QJsonDocument::fromJson(text.toUtf8());
    const auto payload = document.object();
    const double pid = payload.value("pid").toDouble(-1);
    const double windowId = payload.value("windowId").toDouble(-1);
    const QString sender = message().service();
    if (!std::isfinite(pid) || pid < 1 || pid > 2147483647 || std::floor(pid) != pid ||
        !std::isfinite(windowId) || windowId < 1 || windowId > 4294967295.0 || std::floor(windowId) != windowId)
        return QStringLiteral("WAIT 词岛进程或窗口标识无效");
    if (m_window && m_sender != sender) return QStringLiteral("WAIT 已有词岛界面连接");
    if (m_window && (m_window->pid() != qint64(pid) || m_window->windowId() != qlonglong(windowId)))
        return QStringLiteral("WAIT 词岛窗口标识发生变化");
    if (!m_window) {
        const QDBusReply<uint> caller = connection().interface()->servicePid(sender);
        if (!caller.isValid()) return QStringLiteral("WAIT 无法验证桥接进程");
        QFile status(QStringLiteral("/proc/%1/status").arg(caller.value()));
        if (!status.open(QIODevice::ReadOnly)) return QStringLiteral("WAIT 无法验证桥接进程");
        const auto parent = QRegularExpression(QStringLiteral("^PPid:\\s+(\\d+)$"),
            QRegularExpression::MultilineOption).match(QString::fromUtf8(status.read(8192)));
        if (!parent.hasMatch() || parent.captured(1).toLongLong() != qint64(pid))
            return QStringLiteral("WAIT 桥接进程不属于词岛界面");
    }
    SpwIsland::Contour contour;
    contour.maskAlpha = payload.value("maskAlpha").toDouble(-1);
    contour.opacity = payload.value("opacity").toDouble(-1);
    const auto points = payload.value("points").toArray();
    if (!std::isfinite(contour.maskAlpha) || contour.maskAlpha <= 0 || contour.maskAlpha > 1 ||
        !std::isfinite(contour.opacity) || contour.opacity < 0 || contour.opacity > 1 ||
        points.size() < 3 || points.size() > 256) return QStringLiteral("WAIT 轮廓数据无效");
    for (const auto &value : points) {
        const auto point = value.toArray();
        if (point.size() != 2 || !point[0].isDouble() || !point[1].isDouble())
            return QStringLiteral("WAIT 轮廓坐标无效");
        const double x = point[0].toDouble(), y = point[1].toDouble();
        if (!std::isfinite(x) || !std::isfinite(y) || x < -2 || x > 3 || y < -2 || y > 3)
            return QStringLiteral("WAIT 轮廓坐标超出范围");
        contour.points.emplace_back(x, y);
    }
    if (!m_window) {
        for (auto window : effects->stackingOrder()) {
            if (window->pid() == qint64(pid) && window->windowId() == qlonglong(windowId)) {
                m_window = window;
                break;
            }
        }
        if (!m_window) return QStringLiteral("WAIT 等待词岛窗口");
        m_sender = sender;
        m_background = std::make_unique<BackgroundEffectItem>(m_window->windowItem());
        m_background->setPixelsToExpandRepaintsBelowOpaqueRegions(SpwIsland::GlassRenderer::padding);
        m_lease.start();
    }
    m_contour = std::move(contour);
    m_updated = m_clock.elapsed();
    const auto size = m_window->frameGeometry().size();
    const int padding = SpwIsland::GlassRenderer::padding;
    m_background->setEffectBoundingRect(RectF(-padding, -padding,
        size.width() + padding * 2, size.height() + padding * 2));
    m_window->addRepaintFull();
    if (m_clock.elapsed() - m_painted < 1500)
        return m_notice.isEmpty() ? QStringLiteral("READY") : QStringLiteral("READY ") + m_notice;
    return QStringLiteral("WAIT ") + (m_notice.isEmpty() ? QStringLiteral("等待 KWin 合成器首帧") : m_notice);
}

void SpwIslandGlassEffect::Release() {
    if (m_sender == message().service()) clearWindow();
}

void SpwIslandGlassEffect::clearWindow() {
    m_lease.stop();
    if (m_window) m_window->addRepaintFull();
    m_window = nullptr;
    m_sender.clear();
    m_background.reset();
    m_painted = -10000;
    // Releasing the lease also releases all per-view GPU images; no idle repaint loop survives.
    effects->makeOpenGLContextCurrent();
    if (m_candidate) m_candidate->clearFrames();
    if (m_renderer) m_renderer->clearFrames();
}

#ifdef SPW_KWIN_67
void SpwIslandGlassEffect::prePaintScreen(ScreenPrePaintData &data) {
    m_view = data.view;
    effects->prePaintScreen(data);
}
#else
void SpwIslandGlassEffect::prePaintScreen(ScreenPrePaintData &data, std::chrono::milliseconds time) {
    m_view = data.view;
    effects->prePaintScreen(data, time);
}
#endif

void SpwIslandGlassEffect::paintGlass(const RenderTarget &target, const RenderViewport &viewport,
    EffectWindow *window, int mask, const Region &damage, WindowPaintData &data) {
    if (window != m_window || m_drawingMask) return;
    // Mask rendering re-enters the effect chain; it must never capture its own offscreen target.
    m_drawingMask = true;
    try {
        bool painted = false;
        if (m_candidate) {
            try {
                painted = m_candidate->paint(target, viewport, m_view, window, mask, damage, data, m_contour);
                if (painted) {
                    m_renderer = std::move(m_candidate);
                    m_revision = m_candidateRevision;
                    m_candidateRevision.clear();
                    m_notice.clear();
                }
            } catch (const std::exception &error) {
                m_candidate.reset();
                m_candidateRevision.clear();
                m_notice = QStringLiteral("KWin 新材质未就绪，保留当前材质：") + QString::fromUtf8(error.what());
            }
        }
        if (!painted && m_renderer)
            painted = m_renderer->paint(target, viewport, m_view, window, mask, damage, data, m_contour);
        if (painted) m_painted = m_clock.elapsed();
    } catch (const std::exception &error) {
        m_notice = QStringLiteral("KWin 材质绘制失败：") + QString::fromUtf8(error.what());
        m_painted = -10000;
    }
    m_drawingMask = false;
}

#ifdef SPW_KWIN_68
bool SpwIslandGlassEffect::drawWindow(const RenderTarget &target, const RenderViewport &viewport,
    EffectWindow *window, int mask, const Region &damage, WindowPaintData &data) {
    paintGlass(target, viewport, window, mask, damage, data);
    return effects->drawWindow(target, viewport, window, mask, damage, data);
}
#else
void SpwIslandGlassEffect::drawWindow(const RenderTarget &target, const RenderViewport &viewport,
    EffectWindow *window, int mask, const Region &damage, WindowPaintData &data) {
    paintGlass(target, viewport, window, mask, damage, data);
    effects->drawWindow(target, viewport, window, mask, damage, data);
}
#endif
}
