const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const { getEventListeners } = require('node:events');
const script = fs.readFileSync(path.join(__dirname, '../../main/assets/dsh-compat/standard-bootstrap.js'), 'utf8');
const probeScript = fs.readFileSync(path.join(__dirname, '../../../../api/src/main/assets/dsh-renderer/capability-probe.js'), 'utf8');
const hostPromiseMethod = Promise.withResolvers;
const hostPromiseTryMethod = Promise.try;
const hostSignalMethod = AbortSignal.any;
// The alias exposes native brand-checking getters but never changes native globals.
function SignalAlias() {}
Object.defineProperties(SignalAlias.prototype, {
  aborted: Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'aborted'),
  reason: Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'reason'),
});
const context = vm.createContext({
  AbortSignal: SignalAlias,
  AbortController,
  EventTarget,
  URL,
});
vm.runInContext(`
  delete Promise.withResolvers;
  delete Promise.try;
  delete Array.prototype.toSorted;
  delete Array.prototype.findLast;
  delete Array.prototype.findLastIndex;
  delete Array.prototype.toReversed;
  delete URL.parse;
  delete URL.canParse;
  delete Map.prototype.getOrInsert;
  delete Map.prototype.getOrInsertComputed;
  delete RegExp.escape;
`, context);
vm.runInContext(script, context);
vm.runInContext(`
  var original = [3, 1, 2];
  var sorted = original.toSorted((a, b) => a - b);
  if (sorted.join() !== '1,2,3' || original.join() !== '3,1,2') throw Error('Mutation or comparator');
  var holes = Array(2).toSorted();
  if (!(0 in holes) || !(1 in holes)) throw Error('Sparse slots must become undefined');
  var generic = Array.prototype.toSorted.call({0: 'b', 1: 'a', length: 2});
  if (generic.join() !== 'a,b') throw Error('Generic array-like');
  var species = class extends Array { static get [Symbol.species]() { throw Error('Species accessed'); } };
  if (new species(2, 1).toSorted() instanceof species) throw Error('Species inherited');
  for (var operation of [
    () => Array.prototype.toSorted.call(null),
    () => [1].toSorted(null),
    () => Array.prototype.toSorted.call({length: 1n})
  ]) {
    var threw = false;
    try { operation(); } catch (error) { threw = error instanceof TypeError; }
    if (!threw) throw Error('Expected TypeError');
  }
  var capability = Promise.withResolvers();
  capability.resolve(42);
  var subclass = class extends Promise {};
  if (!(Promise.withResolvers.call(subclass).promise instanceof subclass)) throw Error('Promise subclass');
  var trySubclass = class extends Promise {};
  var tryValue = Promise.try.call(trySubclass, (left, right) => left + right, 20, 22);
  if (!(tryValue instanceof trySubclass)) throw Error('Promise.try subclass');
  var values = [1, 4, 2, 6];
  if (values.findLast((value) => value % 2 === 0) !== 6) throw Error('Array.findLast');
  if (values.findLastIndex((value) => value < 5) !== 2) throw Error('Array.findLastIndex');
  var receiver = { minimum: 3 };
  var receiverValue = values.findLast(function (value) {
    return value >= this.minimum;
  }, receiver);
  if (receiverValue !== 6) throw Error('Array.findLast thisArg');
  var reversed = values.toReversed();
  if (reversed.join() !== '6,2,4,1' || values.join() !== '1,4,2,6') {
    throw Error('Array.toReversed mutation');
  }
  var parsed = URL.parse('../asset.js', 'http://127.0.0.1:3080/app/index.html');
  if (!parsed || parsed.href !== 'http://127.0.0.1:3080/asset.js') throw Error('URL.parse');
  if (URL.parse('http://[invalid') !== null) throw Error('URL.parse failure');
  if (!URL.canParse('https://example.com/') || URL.canParse('http://[invalid')) {
    throw Error('URL.canParse');
  }
  var upserts = new Map();
  if (upserts.getOrInsert('a', 1) !== 1 || upserts.getOrInsert('a', 2) !== 1) {
    throw Error('Map.getOrInsert');
  }
  var computedCalls = 0;
  var computed = upserts.getOrInsertComputed('b', function (key) {
    computedCalls += 1;
    return key + ':value';
  });
  if (computed !== 'b:value' || computedCalls !== 1) throw Error('Map.getOrInsertComputed');
  if (upserts.getOrInsertComputed('b', function () { throw Error('recomputed'); }) !== 'b:value') {
    throw Error('Map.getOrInsertComputed existing value');
  }
  var escaped = RegExp.escape('foo.bar-baz');
  if (escaped !== '\\\\x66oo\\\\.bar\\\\x2dbaz') throw Error('RegExp.escape');
  if (!new RegExp('^' + escaped + '$', 'u').test('foo.bar-baz')) {
    throw Error('RegExp.escape semantics');
  }
  if (Object.keys(Promise).includes('withResolvers') ||
      Object.keys(Promise).includes('try') ||
      Object.keys(Array.prototype).includes('toSorted') ||
      Object.keys(Array.prototype).includes('findLast') ||
      Object.keys(Array.prototype).includes('toReversed') ||
      Object.keys(URL).includes('parse') ||
      Object.keys(Map.prototype).includes('getOrInsertComputed') ||
      Object.keys(RegExp).includes('escape')) throw Error('Enumerable polyfill');
`, context);
const any = SignalAlias.any;
assert.equal(typeof any, 'function');
assert.notEqual(any, hostSignalMethod);
assert.equal(Object.getOwnPropertyDescriptor(SignalAlias, 'any').enumerable, false);
for (const invalid of [null, undefined, 42, {}, true, [{ aborted: false }]]) {
  assert.throws(() => any(invalid), error => error.name === 'TypeError');
}
assert.equal(any([]).aborted, false);
const reasonA = { reason: 'A' };
const a = new AbortController();
const b = new AbortController();
a.abort(reasonA);
b.abort('B');
assert.equal(any([a.signal, b.signal]).reason, reasonA);
assert.equal(any([b.signal, a.signal]).reason, 'B');
// Even after encountering an aborted signal, later invalid elements must throw.
assert.throws(() => any([a.signal, {}]), error => error.name === 'TypeError');
let iteratorClosed = false;
function* invalidSequence() { try { yield {}; } finally { iteratorClosed = true; } }
assert.throws(() => any(invalidSequence()), error => error.name === 'TypeError');
assert.equal(iteratorClosed, true);
const c = new AbortController();
const d = new AbortController();
const merged = any(new Set([c.signal, d.signal]));
// User overrides and forged events must not affect intrinsic state or listeners.
c.signal.addEventListener = () => { throw Error('Overridden method used'); };
const another = any([c.signal, c.signal]);
c.signal.dispatchEvent(new Event('abort'));
assert.equal(merged.aborted, false);
const reasonD = { reason: 'D' };
d.abort(reasonD);
assert.equal(merged.reason, reasonD);
c.abort('C');
assert.equal(merged.reason, reasonD);
assert.equal(another.reason, 'C');
assert.equal(getEventListeners(c.signal, 'abort').length, 0);
assert.equal(getEventListeners(d.signal, 'abort').length, 0);
const foreign = vm.runInNewContext('new AbortController()', { AbortController });
assert.equal(foreign.signal instanceof SignalAlias, false);
const branded = any([foreign.signal]);
foreign.abort('foreign');
assert.equal(branded.reason, 'foreign');
vm.runInContext('window = globalThis; window.top = window; navigator = {userAgent: "probe-test"};', context);
const keysBeforeProbe = Object.keys(context);
const report = vm.runInContext(probeScript, context);
assert.deepEqual(Object.keys(context), keysBeforeProbe);
assert.equal(Object.keys(report.effective).length, 21);
assert.deepEqual(Object.keys(report.native).sort(), Object.keys(report.effective).sort());
for (const name of [
  'PROMISE_WITH_RESOLVERS',
  'PROMISE_TRY',
  'ARRAY_TO_SORTED',
  'ARRAY_FIND_LAST',
  'ARRAY_TO_REVERSED',
  'ABORT_SIGNAL_ANY',
  'URL_PARSE',
  'URL_CAN_PARSE',
  'REGEXP_ESCAPE',
  'MAP_GET_OR_INSERT',
  'MAP_GET_OR_INSERT_COMPUTED',
]) {
  assert.equal(report.native[name], false);
  assert.equal(report.effective[name], true);
}
assert.equal(report.userAgent, 'probe-test');
const iframe = vm.createContext({window: {top: {}}});
vm.runInContext('Promise.withResolvers = undefined;', iframe);
vm.runInContext(script, iframe);
assert.equal(vm.runInContext('Promise.withResolvers', iframe), undefined);
assert.equal(vm.runInContext(probeScript, iframe), null);
function MissingWeakSignal() {}
Object.defineProperties(MissingWeakSignal.prototype, {
  aborted: Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'aborted'),
  reason: Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'reason'),
});
const missingWeak = vm.createContext({AbortSignal: MissingWeakSignal, AbortController, EventTarget,
  WeakRef: undefined, FinalizationRegistry: undefined});
vm.runInContext(script, missingWeak);
assert.equal(MissingWeakSignal.any, undefined);
const rejectedTry = vm.runInContext(
  "Promise.try(() => { throw new Error('sync-try'); })",
  context,
);
Promise.all([
  Promise.resolve(context.capability.promise),
  Promise.resolve(context.tryValue),
  rejectedTry.then(
    () => { throw new Error('Promise.try did not reject'); },
    error => error.message,
  ),
]).then(([value, tryValue, rejection]) => {
  assert.equal(value, 42);
  assert.equal(tryValue, 42);
  assert.equal(rejection, 'sync-try');
  const installed = vm.runInContext('Promise.withResolvers', context);
  const installedTry = vm.runInContext('Promise.try', context);
  vm.runInContext(script, context);
  assert.equal(vm.runInContext('Promise.withResolvers', context), installed);
  assert.equal(vm.runInContext('Promise.try', context), installedTry);
  assert.equal(Promise.withResolvers, hostPromiseMethod);
  assert.equal(Promise.try, hostPromiseTryMethod);
  assert.equal(AbortSignal.any, hostSignalMethod);
  assert.equal(vm.runInContext(probeScript, context).native.PROMISE_WITH_RESOLVERS, false);
  assert.equal(vm.runInContext(probeScript, context).native.PROMISE_TRY, false);
  console.log('standard-bootstrap semantic checks passed: Promise, Array, URL, collection, AbortSignal');
}).catch(error => { console.error(error); process.exitCode = 1; });
