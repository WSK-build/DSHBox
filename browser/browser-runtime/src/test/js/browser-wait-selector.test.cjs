const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

const LIMIT = 5000;
const kotlinSource = fs.readFileSync(path.resolve(__dirname,
  '../../main/java/com/dshbox/browser/runtime/BrowserWaitScript.kt'), 'utf8');
const selectorTemplate = kotlinSource.match(
  /private fun selector\(selector: String, state: String\): String = """([\s\S]*?)"""\.trimIndent\(\)\.replace\("__SELECTOR_LIMIT__"/
)?.[1];
assert.ok(selectorTemplate, 'production selector script template must be present');
assert.equal(selectorTemplate.includes('querySelectorAll'), false);

function probe(elements, selector, state) {
  const document = {
    documentElement: { matches(value) { validate(value); return false; } },
    createTreeWalker() {
      let index = 0;
      return { nextNode: () => elements[index++] || null };
    },
  };
  const sandbox = {
    document,
    NodeFilter: { SHOW_ELEMENT: 1 },
    window: { getComputedStyle: element => element.style },
  };
  const source = selectorTemplate
    .replace(/^\s*\n/, '').replace(/\n\s*$/, '')
    .split('\n').map(line => line.replace(/^ {8}/, '')).join('\n')
    .replace('${JSONObject.quote(selector)}', JSON.stringify(selector))
    .replace('${JSONObject.quote(state)}', JSON.stringify(state))
    .replace('__SELECTOR_LIMIT__', String(LIMIT));
  return vm.runInNewContext(source, sandbox);
}

function element(classes, style = {}) {
  return {
    nodeType: 1,
    parentElement: null,
    style: { display: 'block', visibility: 'visible', opacity: '1', ...style },
    matches(selector) {
      if (selector === '[') throw new SyntaxError('invalid selector');
      return selector.split(',').some(part => part.trim().startsWith('.') && classes.includes(part.trim().slice(1)));
    },
    getBoundingClientRect: () => ({ width: 10, height: 10 }),
    getClientRects: () => [1],
    hasAttribute: () => false,
    getAttribute: () => null,
  };
}

function validate(selector) {
  if (selector === '[') throw new SyntaxError('invalid selector');
}

test('reports attached, visible, hidden, and invalid selector outcomes', () => {
  assert.equal(probe([element(['result'], { display: 'none' })], '.result', 'attached'), true);
  assert.equal(probe([element(['result'], { display: 'none' })], '.result', 'visible'), false);
  assert.equal(probe([element(['result'], { display: 'none' })], '.result', 'hidden'), true);
  assert.equal(probe([element(['result'])], '.result', 'hidden'), false);
  assert.equal(probe([], '[', 'attached'), 'INVALID_SELECTOR');
});

test('rejects case-insensitive and CSS-escaped scope pseudo classes', () => {
  assert.equal(probe([], ':SCOPE > .result', 'attached'), 'INVALID_SELECTOR');
  assert.equal(probe([], ':\\73 cope > .result', 'attached'), 'INVALID_SELECTOR');
  assert.equal(probe([], ':\\000073cope > .result', 'attached'), 'INVALID_SELECTOR');
  assert.equal(probe([], ':s\\\ncope > .result', 'attached'), 'INVALID_SELECTOR');
  assert.equal(probe([], ':is(:SCOPE)', 'attached'), 'INVALID_SELECTOR');
});

test('does not confuse attribute strings or CSS comments with a scope pseudo class', () => {
  assert.equal(probe([], '[data-label=":scope"]', 'attached'), false);
  assert.equal(probe([], '/* :scope */ .result', 'attached'), false);
});

test('returns a limit result when a negative result would require scanning past the bound', () => {
  const many = Array.from({ length: LIMIT + 1 }, () => element(['other']));
  assert.equal(probe(many, '.result', 'hidden'), 'WAIT_PROBE_LIMIT');
  assert.equal(probe(many, '.result', 'visible'), 'WAIT_PROBE_LIMIT');
});

test('can return a positive result before exhausting the scan bound', () => {
  const many = Array.from({ length: LIMIT + 1 }, () => element(['other']));
  many[12] = element(['result']);
  assert.equal(probe(many, '.result', 'visible'), true);
  assert.equal(probe(many, '.result', 'attached'), true);
});
