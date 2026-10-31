// SPDX-License-Identifier: GPL-3.0-only
#include "IslandGlassEffect.h"
#include "effect/effecthandler.h"

namespace KWin {
KWIN_EFFECT_FACTORY_SUPPORTED(SpwIslandGlassEffect, "metadata.json",
                              return effects->isOpenGLCompositing();)
}
#include "main.moc"
