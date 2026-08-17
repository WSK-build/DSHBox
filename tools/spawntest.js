// Live repro for DSH-in-Android-PRoot issues: execve ENOENT, link EACCES, /proc.
const { spawnSync } = require('child_process')
const fs = require('fs')

function t(label, cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', env: process.env, ...opts })
  console.log(label, JSON.stringify({
    status: r.status,
    signal: r.signal,
    error: r.error ? r.error.code : null,
    stdout: (r.stdout || '').slice(0, 160).trim(),
    stderr: (r.stderr || '').slice(0, 160).trim(),
  }))
}

console.log('cwd=', process.cwd())
console.log('PATH=', process.env.PATH)
console.log('execPath=', process.execPath)
t('abs /usr/bin/bash', '/usr/bin/bash', ['-c', 'echo ok'])
t('bare bash', 'bash', ['-c', 'echo ok'])
t('/bin/sh', '/bin/sh', ['-c', 'echo ok'])
t('node self', process.execPath, ['-e', 'console.log("self ok")'])
t('which ls', '/usr/bin/which', ['ls'])
t('rg', '/usr/local/bin/rg', ['--version'])

try {
  console.log('proc/version=', fs.readFileSync('/proc/version', 'utf8').trim())
} catch (e) { console.log('proc/version ERR', e.code, e.message) }
try {
  fs.linkSync('/root/projects/spawntest.js', '/root/projects/linktest.js')
  console.log('link ok')
} catch (e) { console.log('link ERR', e.code, e.message) }
try {
  fs.writeFileSync('/root/projects/newfile.txt', 'hi')
  console.log('write-new ok')
} catch (e) { console.log('write-new ERR', e.code, e.message) }
try {
  fs.renameSync('/root/projects/newfile.txt', '/root/projects/renamed.txt')
  console.log('rename ok')
} catch (e) { console.log('rename ERR', e.code, e.message) }
