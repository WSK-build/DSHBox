#!/usr/bin/env node
// Android compatibility patch for DSH hard-link usage.
//
// Android app-data filesystems (FBE/FUSE) deny hard links with EACCES. DSH
// uses link()+unlink() as its no-clobber publish primitive in a few places,
// which breaks on Android. This patch makes those paths fall back to rename()
// when the platform denies hard links.
//
// The patch is applied to the installed DSH packages inside the runtime rootfs;
// it does not change the upstream source repository.

const fs = require('node:fs')

function patchFile(target, marker, importOld, importNew, oldBlock, newBlock) {
  const source = fs.readFileSync(target, 'utf8')

  if (source.includes(marker)) {
    console.log(`[patch] already applied: ${target}`)
    return
  }

  let patched = source
  if (importOld !== null) {
    if (!source.includes(importOld)) {
      console.error(`[patch] unexpected import in ${target}; aborting`)
      process.exit(1)
    }
    patched = source.replace(importOld, importNew)
  }

  if (!patched.includes(oldBlock)) {
    console.error(`[patch] unexpected block in ${target}; aborting`)
    process.exit(1)
  }
  patched = patched.replace(oldBlock, newBlock)

  fs.writeFileSync(target, patched)
  console.log(`[patch] applied: ${target}`)
}

const runtimeRoot = process.argv[2] || '/opt/dshapp/runtime/node_modules/@deepseek-ai'

// 1. dsh-session-persistence-jsonl: new session files are published with link().
patchFile(
  `${runtimeRoot}/dsh-session-persistence-jsonl/lib/index.js`,
  'Android app-data filesystems deny hard links',
  'import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";',
  'import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate } from "node:fs/promises";',
  `\t\ttry {
\t\t\tawait link(tmp, finalPath);
\t\t\tlinked = true;
\t\t} finally {
\t\t\t/* v8 ignore next -- link failure is the TOCTOU/IO race guarded above; not reachable in test */
\t\t\tif (!linked) await rm(tmp, { force: true });
\t\t}`,
  `\t\ttry {
\t\t\tawait link(tmp, finalPath);
\t\t\tlinked = true;
\t\t} catch (error) {
\t\t\t// Android app-data filesystems deny hard links. Fall back to rename:
\t\t\t// rejectExistingLog already guarantees the target does not exist.
\t\t\tif (error && (error.code === "EACCES" || error.code === "EPERM" || error.code === "ENOTSUP" || error.code === "ENOSYS")) {
\t\t\t\tawait rename(tmp, finalPath);
\t\t\t\tlinked = true;
\t\t\t} else {
\t\t\t\tthrow error;
\t\t\t}
\t\t} finally {
\t\t\t/* v8 ignore next -- link failure is the TOCTOU/IO race guarded above; not reachable in test */
\t\t\tif (!linked) await rm(tmp, { force: true });
\t\t}`,
)

// 2a. Make the attachment cleanup tolerant of rename() having already moved
// the temp file away (Android fallback).
const attachmentPath = `${runtimeRoot}/dsh-attachment-local/lib/index.js`
const attachmentSource = fs.readFileSync(attachmentPath, 'utf8')
const oldUnlink = '\t\tawait unlink(temporary);'
const newUnlink = '\t\tawait unlink(temporary).catch(() => {});'
if (attachmentSource.includes(oldUnlink) && !attachmentSource.includes(newUnlink)) {
  fs.writeFileSync(attachmentPath, attachmentSource.replace(oldUnlink, newUnlink))
  console.log('[patch] attachment unlink cleanup made tolerant')
}

// 2. dsh-attachment-local: immutable attachment objects are published with link().
// Marker is the PATCHED import line: files patched by older script runs carry
// it too, so re-runs (e.g. hot-patching a live rootfs) are idempotent.
patchFile(
  `${runtimeRoot}/dsh-attachment-local/lib/index.js`,
  'import { chmod, link, mkdir, open, readFile, rename, unlink } from "node:fs/promises";',
  'import { chmod, link, mkdir, open, readFile, unlink } from "node:fs/promises";',
  'import { chmod, link, mkdir, open, readFile, rename, unlink } from "node:fs/promises";',
  `\t\ttry {
\t\t\tawait link(temporary, target);
\t\t} catch (error) {
\t\t\t/* v8 ignore next -- Private same-filesystem directories make EEXIST the only recoverable link race. */
\t\t\tif (!(error instanceof Error && "code" in error && error.code === "EEXIST")) throw error;
\t\t\tif (digest(new Uint8Array(await readFile(target))) !== sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
\t\t}`,
  `\t\ttry {
\t\t\tawait link(temporary, target);
\t\t} catch (error) {
\t\t\t// Android app-data filesystems deny hard links (attachment). Fall back to rename.
\t\t\tif (error instanceof Error && "code" in error && (error.code === "EACCES" || error.code === "EPERM" || error.code === "ENOTSUP" || error.code === "ENOSYS")) {
\t\t\t\tawait rename(temporary, target);
\t\t\t} else if (!(error instanceof Error && "code" in error && error.code === "EEXIST")) {
\t\t\t\tthrow error;
\t\t\t} else {
\t\t\t\t/* v8 ignore next -- Private same-filesystem directories make EEXIST the only recoverable link race. */
\t\t\t\tif (digest(new Uint8Array(await readFile(target))) !== sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
\t\t\t}
\t\t}`,
)

// 3. dsh-fs-local: the model-facing write/edit tool publishes NEW files with a
// hard-link no-replace primitive (writeFileAtomic createIfAbsent). Android
// app-data filesystems deny link() with EACCES, so creating any new file via
// the write tool failed with "EACCES: permission denied, link ...". Fall back
// to rename() when the platform denies hard links AND the target is still
// absent (the no-replace intent is preserved; a concurrent creator's file is
// still detected and rejected with FS_NOT_OBSERVED).
patchFile(
  `${runtimeRoot}/dsh-fs-local/lib/index.js`,
  'Android app-data filesystems deny hard links (fs-local)',
  null,
  null,
  `\t\tif (createIfAbsent !== void 0) try {
\t\t\tawait linkFile(tempPath, absolutePath);
\t\t} catch (error) {
\t\t\tawait throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
\t\t}`,
  `\t\tif (createIfAbsent !== void 0) try {
\t\t\tawait linkFile(tempPath, absolutePath);
\t\t} catch (error) {
\t\t\t// Android app-data filesystems deny hard links (fs-local). Fall back
\t\t\t// to rename() when the target is still absent (no-replace intent
\t\t\t// preserved); otherwise keep the guarded collision handling.
\t\t\tif (error && (error.code === "EACCES" || error.code === "EPERM" || error.code === "ENOTSUP" || error.code === "ENOSYS")) {
\t\t\t\tlet existing = null;
\t\t\t\ttry {
\t\t\t\t\texisting = await inspectPublicationTarget(absolutePath);
\t\t\t\t} catch (inspectError) {
\t\t\t\t\tif (!isENOENT(inspectError) && !isENOTDIR(inspectError)) throw inspectError;
\t\t\t\t}
\t\t\t\tif (existing === null) await rename(tempPath, absolutePath);
\t\t\t\telse await throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
\t\t\t} else {
\t\t\t\tawait throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
\t\t\t}
\t\t}`,
)
