// SPDX-License-Identifier: GPL-3.0-only
import Gio from 'gi://Gio';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

const INTERFACE = `<node><interface name="io.github.gaboron.SpwIsland.Pointer">
    <method name="GetPointer"><arg type="d" direction="out"/><arg type="d" direction="out"/></method>
</interface></node>`;

/** Read-only, on-demand pointer access. No timer, input capture, or Shell patches. */
export default class SpwIslandPointer extends Extension {
    enable() {
        this._service = Gio.DBusExportedObject.wrapJSObject(INTERFACE, {
            GetPointer() {
                const [x, y] = global.get_pointer();
                return [x, y];
            },
        });
        this._service.export(Gio.DBus.session, '/io/github/gaboron/SpwIsland/Pointer');
    }

    disable() {
        this._service?.unexport();
        this._service = null;
    }
}
