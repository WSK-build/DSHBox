/**
 * Host loader entry for the browser-only mobile-adapt plugin.
 * Provides no host-side behavior; its only job is to be a cordis Loader
 * entry so the client-modules node half discovers the `dsh.client`
 * declaration and serves the browser half at /plugins/@local/dsh-mobile-adapt/client.js.
 */
'use strict'

/** Stable cordis plugin name — must equal the package name so the
 * client-modules node half can resolvePkgJson('@local/dsh-mobile-adapt'). */
exports.name = '@local/dsh-mobile-adapt'

/** No host-side services required. */
exports.inject = []

/** No host-side behavior. */
exports.apply = function apply() {}
