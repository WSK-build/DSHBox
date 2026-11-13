import fsp from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { execFile } from 'node:child_process';
import { constants } from 'node:os';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { dirname, join, basename } from 'node:path';

const execute = promisify(execFile);
const helper = fileURLToPath(new URL('./lock-helper', import.meta.url));
const writeFile = fsp.writeFile.bind(fsp);
const ownRecord = `${process.pid}\n`;

export async function writeWithPublishedLock(filename, content, options) {
  const path = filename instanceof URL ? fileURLToPath(filename) : filename;
  if (typeof path !== 'string' || !path.endsWith('.lock') || options?.flag !== 'wx' ||
      !(typeof content === 'string' || Buffer.isBuffer(content)) || content.toString() !== ownRecord) {
    return writeFile(filename, content, options);
  }
  const temporary = join(dirname(path), `.dshbox-lock-${process.pid}-${randomBytes(12).toString('hex')}.tmp`);
  const handle = await fsp.open(temporary, 'wx', options.mode ?? 0o666);
  const identity = await handle.stat({ bigint: true });
  let acquired = false;
  const published = async () => {
    const current = await fsp.lstat(path, { bigint: true }).catch(() => null);
    return current?.dev === identity.dev && current?.ino === identity.ino;
  };
  try {
    await handle.writeFile(content, options);
    options?.signal?.throwIfAborted();
    try {
      await execute(helper, ['publish', temporary, path, String(process.pid)]);
      acquired = true;
    } catch (error) {
      // Child exit can be ambiguous after rename; compare the still-open inode, not PID text.
      if (await published()) { acquired = true; return; }
      const errno = Number(error.stdout?.trim());
      if (errno === constants.errno.EEXIST && basename(path) === '.credentials.yaml.lock') {
        // Recovery requires a kernel lease, not an age or a process-name guess.
        const recovered = await execute(helper, ['recover-empty', path]).then(() => true, () => false);
        if (recovered) {
          try {
            await execute(helper, ['publish', temporary, path, String(process.pid)]);
            acquired = true;
            return;
          } catch (retryError) {
            if (await published()) { acquired = true; return; }
            error = retryError;
          }
        }
      }
      const code = Object.entries(constants.errno).find(([, value]) => value === Number(error.stdout?.trim()))?.[0];
      if (code) {
        error.code = code;
        error.path = path;
        error.syscall = 'renameat2';
      }
      throw error;
    }
  } finally {
    // Once acquired, cleanup failure must not hide ownership from withFileLock's release path.
    await handle.close().catch(() => {});
    if (!acquired) await fsp.unlink(temporary).catch(() => {});
  }
}
