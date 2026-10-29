# SPDX-License-Identifier: GPL-3.0-only
"""Geometry-only GNOME bridge. Desktop images never cross this pipe or D-Bus."""
import json
import runpy
import sys

Bus = runpy.run_path(sys.argv[1])["Bus"]
bus = Bus()
interface = "io.github.gaboron.SpwIsland.Glass"
path = "/io/github/gaboron/SpwIsland/Glass"

try:
    for line in sys.stdin:
        try:
            # Validate framing before forwarding; the Shell validates the bounded contour.
            json.loads(line)
            reply = bus.first(bus.call("org.gnome.Shell", path, interface, "Update",
                                       (line.strip(),), "(s)"), lambda value: bus.get_string(value, None))
            print(reply.decode("utf-8", "replace"), flush=True)
        except (OSError, RuntimeError, ValueError) as error:
            print("WAIT " + str(error).replace("\n", " "), flush=True)
finally:
    try:
        reply = bus.call("org.gnome.Shell", path, interface, "Release", (), "()")
        bus.unref(reply)
    except (OSError, RuntimeError):
        pass
