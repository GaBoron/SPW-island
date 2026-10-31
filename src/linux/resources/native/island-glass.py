# SPDX-License-Identifier: GPL-3.0-only
"""Geometry-only compositor bridge. Desktop images never cross this pipe or D-Bus."""
import json
import os
import runpy
import sys

Bus = runpy.run_path(sys.argv[1])["Bus"]
bus = Bus()
interface = "io.github.gaboron.SpwIsland.Glass"
path = "/io/github/gaboron/SpwIsland/Glass"
kde = 'kde' in os.environ.get('XDG_CURRENT_DESKTOP', '').lower().split(':')
service = 'org.kde.KWin' if kde else 'org.gnome.Shell'
if kde:
    path = '/io/github/gaboron/SpwIsland/KWinGlass'

try:
    for line in sys.stdin:
        try:
            # Validate framing before forwarding; the Shell validates the bounded contour.
            json.loads(line)
            # A distinct method prevents GNOME's cached older module from receiving a render request.
            reply = bus.first(bus.call(service, path, interface, "UpdateManaged",
                                       (line.strip(),), "(s)"), lambda value: bus.get_string(value, None))
            print(reply.decode("utf-8", "replace"), flush=True)
        except (OSError, RuntimeError, ValueError) as error:
            message = str(error).replace("\n", " ")
            if any(kind in message for kind in ('UnknownMethod', 'UnknownObject', 'UnknownInterface')):
                message = ('请从插件设置安装 / 修复 Linux 液态玻璃支持，以加载匹配本机 KWin 的组件。' if kde else
                           '请从插件设置安装 / 修复 Linux 液态玻璃支持；首次安装或旧入口迁移后重新登录一次，此后渲染更新自动同步。')
            print("WAIT " + message, flush=True)
finally:
    try:
        reply = bus.call(service, path, interface, "Release", (), "()")
        bus.unref(reply)
    except (OSError, RuntimeError):
        pass
