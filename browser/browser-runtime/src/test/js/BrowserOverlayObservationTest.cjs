const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname,
  '../../main/java/com/dshbox/browser/runtime/BrowserOverlayObservation.kt'), 'utf8');

function productionScript() {
  const start = source.indexOf('fun script(): String = """');
  assert.notEqual(start, -1, 'overlay observation template must exist');
  const quote = source.indexOf('"""', start) + 3;
  const end = source.indexOf('""".trimIndent()', quote);
  assert.notEqual(end, -1, 'overlay observation template must terminate');
  return source.slice(quote, end).replace(/^\r?\n/, '').replace(/\r?\n\s*$/, '')
    .split(/\r?\n/).map(line => line.replace(/^ {6}/, '')).join('\n').trim();
}

const script = productionScript();
const plain = value => JSON.parse(JSON.stringify(value));

function element({
  tag = 'DIV',
  rect = {left: 0, top: 0, right: 360, bottom: 676, width: 360, height: 676},
  attributes = {},
  children = [],
  style = {},
  id = '',
  className = '',
  disabled = false,
  open = false,
} = {}) {
  const node = {
    nodeType: 1,
    tagName: tag.toUpperCase(),
    isConnected: true,
    hidden: false,
    disabled,
    open,
    id,
    className,
    parentElement: null,
    childNodes: children,
    labels: [],
    attributes,
    rect,
    style: {
      display: 'block',
      visibility: 'visible',
      contentVisibility: 'visible',
      opacity: '1',
      position: 'static',
      zIndex: 'auto',
      ...style,
    },
    pseudoStyles: {},
    clickCount: 0,
    getAttribute(name) {
      if (name === 'class') return this.className;
      if (name === 'id') return this.id;
      return Object.hasOwn(this.attributes, name) ? this.attributes[name] : null;
    },
    hasAttribute(name) {
      if (name === 'class') return this.className !== '';
      if (name === 'id') return this.id !== '';
      return Object.hasOwn(this.attributes, name);
    },
    getBoundingClientRect() {
      return this.rect;
    },
    getClientRects() {
      return this.rect.width > 0 && this.rect.height > 0 ? [this.rect] : [];
    },
    getRootNode() {
      return this.ownerDocument;
    },
    contains(candidate) {
      for (let current = candidate; current; current = current.parentElement) {
        if (current === this) return true;
      }
      return false;
    },
    closest(selector) {
      if (selector !== 'label') return null;
      for (let current = this.parentElement; current; current = current.parentElement) {
        if (current.tagName === 'LABEL') return current;
      }
      return null;
    },
    matches() {
      return false;
    },
    click() {
      this.clickCount++;
    },
  };
  for (const child of children) child.parentElement = node;
  return node;
}

function fixture({
  elements,
  viewport = {width: 360, height: 676},
  hitStacks = [],
  pseudoThrows = false,
}) {
  const flattened = [];
  const visit = node => {
    flattened.push(node);
    for (const child of node.childNodes || []) visit(child);
  };
  for (const root of elements) visit(root);
  let sampleIndex = 0;
  const document = {
    nodeType: 9,
    documentElement: element({tag: 'HTML'}),
    getElementById() {
      return null;
    },
    createTreeWalker() {
      let index = 0;
      return {
        nextNode() {
          return flattened[index++] || null;
        },
      };
    },
    elementsFromPoint() {
      const stack = hitStacks[Math.min(sampleIndex, Math.max(0, hitStacks.length - 1))] || [];
      sampleIndex++;
      return stack;
    },
    elementFromPoint() {
      const stack = hitStacks[Math.min(sampleIndex, Math.max(0, hitStacks.length - 1))] || [];
      sampleIndex++;
      return stack[0] || null;
    },
  };
  const window = {
    innerWidth: viewport.width,
    innerHeight: viewport.height,
    getComputedStyle(target, pseudo) {
      if (pseudo && pseudoThrows) throw new Error('pseudo style failure');
      if (pseudo) return target.pseudoStyles[pseudo] || {content: 'none'};
      return target.style;
    },
  };
  const context = {
    document,
    window,
    NodeFilter: {SHOW_ELEMENT: 1},
  };
  vm.createContext(context);
  const observe = vm.runInContext(`(input) => (${script})(input)`, context);
  return {observe, document, window};
}

test('reports pseudo content with a source and a hard text bound', () => {
  const overlay = element({attributes: {'aria-modal': 'true'}, className: 'login-dialog'});
  overlay.pseudoStyles['::before'] = {content: `x${'a'.repeat(500)}`};
  overlay.pseudoStyles['::after'] = {content: '"close"'};
  const page = fixture({elements: [overlay]});
  const result = page.observe({registeredActionable: [], referenceMap: []});
  assert.equal(result.overlays.length, 1);
  assert.deepEqual(plain(result.overlays[0].pseudo.map(item => item.source)), ['before', 'after']);
  assert.ok(result.overlays[0].pseudo.every(item => item.boundedContent.length <= 160));
  assert.match(result.overlays[0].pseudo[0].boundedContent, /^x+a/);
});

test('uses bounded viewport samples to report an occlusion ratio', () => {
  const overlay = element({
    attributes: {'aria-modal': 'true'},
    style: {position: 'fixed', zIndex: '1004'},
  });
  const stacks = Array.from({length: 25}, (_, index) => index < 15 ? [overlay] : []);
  const page = fixture({elements: [overlay], hitStacks: stacks});
  const result = page.observe({registeredActionable: [], referenceMap: []});
  assert.equal(result.coverage.viewportSampleCount, 25);
  assert.equal(result.coverage.viewportSampleLimit, 25);
  assert.equal(result.overlays[0].occlusion.sampled, 25);
  assert.equal(result.overlays[0].occlusion.covered, 15);
  assert.equal(result.overlays[0].occlusion.ratio, 0.6);
});

test('returns only enabled close controls backed by current observation indexes', () => {
  const overlay = element({attributes: {'aria-modal': 'true'}, className: 'open-app-dialog'});
  const closeRect = {left: 20, top: 20, right: 50, bottom: 50, width: 30, height: 30};
  const registeredClose = element({
    tag: 'BUTTON',
    className: 'icon-close',
    children: [element({tag: 'SPAN'})],
    rect: closeRect,
  });
  const unregisteredClose = element({tag: 'BUTTON', className: 'icon-dismiss'});
  const disabledClose = element({tag: 'BUTTON', className: 'icon-cancel', disabled: true});
  const blockedClose = element({tag: 'BUTTON', className: 'icon-dismiss', rect: closeRect});
  const outsideClose = element({
    tag: 'BUTTON',
    className: 'icon-close',
    rect: {left: 400, top: 20, right: 430, bottom: 50, width: 30, height: 30},
  });
  overlay.childNodes.push(registeredClose, unregisteredClose, disabledClose, blockedClose, outsideClose);
  registeredClose.parentElement = overlay;
  unregisteredClose.parentElement = overlay;
  disabledClose.parentElement = overlay;
  blockedClose.parentElement = overlay;
  outsideClose.parentElement = overlay;
  assert.equal(overlay.contains(registeredClose), true);
  assert.match(String(registeredClose.className), /close/i);
  const hitStacks = [
    ...Array.from({length: 22}, () => [overlay]),
    [registeredClose],
    [overlay],
  ];
  const page = fixture({elements: [overlay], hitStacks});
  assert.equal(hitStacks[22][0], registeredClose);
  const result = page.observe({
    registeredActionable: [registeredClose, unregisteredClose, disabledClose, blockedClose, outsideClose],
    referenceMap: [[registeredClose, 7], [blockedClose, 8], [outsideClose, 9]],
  });
  assert.deepEqual(plain(result.overlays[0].dismiss), {
    status: 'available',
    candidates: [{
      refIndex: 7,
      tag: 'button',
      role: 'button',
      name: '',
      box: {x: 20, y: 20, width: 30, height: 30},
    }],
  }, JSON.stringify(result));
  assert.equal(result.overlays[0].dismiss.candidates.some(item => item.refIndex === 1), false);
  assert.equal(result.overlays[0].dismiss.candidates.some(item => item.refIndex === 2), false);
  assert.equal(result.overlays[0].dismiss.candidates.some(item => item.refIndex === 8), false);
  assert.equal(result.overlays[0].dismiss.candidates.some(item => item.refIndex === 9), false);
});

test('states explicitly when no registered close reference is available', () => {
  const overlay = element({attributes: {role: 'dialog'}});
  const close = element({tag: 'BUTTON', className: 'close'});
  overlay.childNodes.push(close);
  close.parentElement = overlay;
  const page = fixture({elements: [overlay], hitStacks: [[overlay]]});
  const result = page.observe({registeredActionable: [close], referenceMap: []});
  assert.deepEqual(plain(result.overlays[0].dismiss), {status: 'none', candidates: []});
});

test('enforces candidate, overlay, sample, and output budgets', () => {
  const overlays = Array.from({length: 12}, () => element({
    attributes: {'aria-modal': 'true'},
    style: {position: 'fixed', zIndex: '1000'},
  }));
  const filler = Array.from({length: 300}, () => element());
  const roots = [...overlays, ...filler];
  const page = fixture({elements: roots, hitStacks: [overlays[0]]});
  const result = page.observe({registeredActionable: [], referenceMap: []});
  assert.ok(result.coverage.candidateScan <= 256);
  assert.equal(result.coverage.candidateScanLimit, 256);
  assert.equal(result.coverage.candidateLimitReached, true);
  assert.ok(result.coverage.viewportSampleCount <= 25);
  assert.ok(result.overlays.length <= 8);
  assert.equal(result.coverage.overlayOutputLimit, 8);
  assert.equal(result.coverage.outputTruncated, true);
});

test('reports zero occlusion when an upper overlay covers a lower overlay', () => {
  const lower = element({
    attributes: {'aria-modal': 'true'},
    style: {position: 'fixed', zIndex: '999'},
  });
  const upper = element({
    attributes: {'aria-modal': 'true'},
    style: {position: 'fixed', zIndex: '1000'},
  });
  const page = fixture({elements: [lower, upper], hitStacks: [[upper]]});
  assert.equal(page.document.elementsFromPoint(1, 1)[0], upper);
  const result = page.observe({registeredActionable: [], referenceMap: []});
  const byZIndex = [...result.overlays].sort((left, right) => right.evidence.zIndex - left.evidence.zIndex);
  assert.equal(byZIndex[0].occlusion.ratio, 1, JSON.stringify(result));
  assert.equal(byZIndex[1].occlusion.ratio, 0);
  assert.ok(result.coverage.viewportSampleCount <= 25);
});

test('returns a partial overlay result when pseudo style inspection fails', () => {
  const overlay = element({attributes: {'aria-modal': 'true'}, className: 'modal'});
  overlay.pseudoStyles['::before'] = {content: 'never-read'};
  const page = fixture({elements: [overlay], hitStacks: [[overlay]], pseudoThrows: true});
  const result = page.observe({registeredActionable: [], referenceMap: []});
  assert.equal(result.coverage.partial, true);
  assert.deepEqual(plain(result.coverage.errors), ['bounded_observation_error']);
  assert.equal(result.overlays.length, 1);
  assert.equal(result.overlays[0].dismiss.status, 'none');
});

test('bounds non-text child traversal without reporting a partial result', () => {
  const overlay = element({attributes: {'aria-modal': 'true'}});
  const children = Array.from({length: 200}, () => element({tag: 'SPAN'}));
  for (const child of children) child.parentElement = overlay;
  const page = fixture({elements: [overlay], hitStacks: [[overlay]]});
  overlay.childNodes = new Proxy(children, {
    get(target, property) {
      if (typeof property === 'string' && /^\d+$/.test(property) && Number(property) >= 64) {
        throw new Error('traversal crossed the child node budget');
      }
      return Reflect.get(target, property);
    },
  });
  const result = page.observe({registeredActionable: [], referenceMap: []});
  assert.equal(result.coverage.partial, false);
  assert.deepEqual(plain(result.coverage.errors), []);
});

test('total text and text-node counters remain inside hard budgets', () => {
  const overlays = Array.from({length: 8}, () => element({attributes: {'aria-modal':'true'},
    children:Array.from({length:64}, () => ({nodeType:3,nodeValue:'x'.repeat(512)}))}));
  const page=fixture({elements:overlays,hitStacks:[[overlays[0]]]});
  const result=page.observe({registeredActionable:[],referenceMap:[]});
  assert.ok(result.coverage.textNodesVisited<=512);
  assert.ok(result.coverage.textChars<=8192);
  assert.ok(result.overlays.reduce((total,o)=>total+o.name.length+o.evidence.semanticHint.length+
    o.pseudo.reduce((n,p)=>n+p.boundedContent.length,0),0)<=8192);
});

test('observes without actions, input values, cookies, HTML, or cross-frame claims', () => {
  assert.doesNotMatch(script, /\.(click|dispatchEvent|submit|focus|remove|appendChild)\s*\(/);
  assert.doesNotMatch(script, /\.value\b|document\.cookie|innerHTML|outerHTML/);
  const secretInput = element({
    tag: 'INPUT',
    attributes: {'aria-label': 'Account'},
  });
  secretInput.value = 'secret-input-value';
  const overlay = element({attributes: {'aria-modal': 'true'}, children: [secretInput]});
  const page = fixture({elements: [overlay], hitStacks: [[overlay]]});
  const result = page.observe({registeredActionable: [secretInput], referenceMap: [[secretInput, 0]]});
  const encoded = JSON.stringify(result);
  assert.equal(encoded.includes('secret-input-value'), false);
  assert.equal(secretInput.clickCount, 0);
  assert.deepEqual(plain(result.action), {performed: false, automaticDismiss: false, captchaSolved: false});
  assert.equal(result.scope.topFrameOnly, true);
  assert.equal(result.scope.includesCrossOriginFrames, false);
  assert.equal(result.scope.complete, false);
  assert.equal(result.overlays[0].box.x, 0);
  assert.equal(result.overlays[0].box.y, 0);
  assert.equal(result.overlays[0].box.width, 360);
  assert.equal(result.overlays[0].box.height, 676);
});
