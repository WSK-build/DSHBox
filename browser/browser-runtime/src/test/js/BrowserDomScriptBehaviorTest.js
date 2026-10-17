const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { normalizeResponse } = require('../../../../browser-bridge/src/main/assets/browser/lib/browser-cli.cjs');
const { safeResponse } = require('../../../../../plugin-manager/dshbox-plugins/assets/plugins/browser-use/plugin/lib/client.js');

const sourcePath = path.resolve(__dirname, '../../main/java/com/dshbox/browser/runtime/BrowserDomScript.kt');
const source = fs.readFileSync(sourcePath, 'utf8');
const overlayKotlin = fs.readFileSync(path.resolve(__dirname, '../../main/java/com/dshbox/browser/runtime/BrowserOverlayObservation.kt'), 'utf8');
const overlayScript = overlayKotlin.split('fun script(): String = """')[1].split('""".trimIndent()')[0].trim();

function rawTemplate(startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  assert.notEqual(start, -1, `missing template start: ${startMarker}`);
  const quote = source.indexOf('"""', start);
  assert.notEqual(quote, -1, `missing raw string: ${startMarker}`);
  const end = source.indexOf(endMarker, quote + 3);
  assert.notEqual(end, -1, `missing template end: ${endMarker}`);
  return source.slice(quote + 3, end).replace(/^\r?\n/, '').replace(/\r?\n\s*$/, '');
}

function accessibleWalkerSource() {
  const start = source.indexOf('private fun accessibleWalker(): String = """');
  const end = source.indexOf('""".trimIndent()', start);
  assert(start >= 0 && end > start, 'accessible walker template must exist');
  return source.slice(source.indexOf('"""', start) + 3, end)
    .split(/\r?\n/).map(line => line.replace(/^ {6}/, '')).join('\n').trim();
}

function extractedScriptsParse() {
  const walker = accessibleWalkerSource();
  const locate = rawTemplate('fun locate(', '""".trimIndent().replace("__LIMIT__"')
    .replace('__PARAMS__', JSON.stringify({nonce:'n',previousNonce:null,strategy:'role',value:'Continue',exact:true,name:null,scopeNonce:null,scopeIndex:null}))
    .replace('__LIMIT__', '5000').replace('__TEXT_LIMIT__', '20000').replace('__RELATION_LIMIT__', '20000')
    .replace('__ACCESSIBLE_WALKER__', walker);
  new vm.Script(locate);

  const observe = rawTemplate('fun observe(', '""".trimIndent().replace("__NONCE__"')
    .replace('__NONCE__', '"observe_nonce"').replace('__PREVIOUS_NONCE__', 'null')
    .replace('$maxTextChars', '4096').replace('__ACCESSIBLE_WALKER__', walker)
    .replace('__OVERLAY_OBSERVATION__', overlayScript);
  new vm.Script(observe);

  const prefix = rawTemplate('private fun actionPrefix(', '""".trimIndent().replace("__NONCE__"')
    .replace('__NONCE__', '"observe_nonce"');
  const click = rawTemplate('fun click(', '""".trimIndent().replace("__GATE__"')
    .replaceAll('__GATE__', '"__dshBrowserActionGate"').replaceAll('__AUTHORIZATION_NONCE__', '"auth"');
  new vm.Script(prefix + click);
  const type = rawTemplate('fun type(', '""".trimIndent().replace("__GATE__"')
    .replaceAll('__GATE__', '"__dshBrowserActionGate"').replaceAll('__AUTHORIZATION_NONCE__', '"auth"');
  new vm.Script(prefix + type);
  const scroll = rawTemplate('fun scroll(', '""".trimIndent().replace("__GATE__"')
    .replaceAll('__GATE__', '"__dshBrowserActionGate"').replaceAll('__AUTHORIZATION_NONCE__', '"auth"');
  new vm.Script(prefix + scroll);
  const collect = rawTemplate('fun collectWindow(', '""".trimIndent().replace("__PARAMS__"')
    .replace('__PARAMS__', JSON.stringify({containerSelector:'#messages',messageSelector:'.message',keyAttribute:'data-id',
      authorSelector:'.author',authorAttribute:'',contentSelector:'.body',orderAttribute:'data-order',
      totalCountSelector:'#total',totalCountAttribute:'',maxRecords:20,maxContentChars:100}))
    .replace('__NONCE__','"collect_nonce"').replace('__PREVIOUS_NONCE__','null');
  new vm.Script(collect);
  process.stdout.write('TEMPLATE_SYNTAX_OK\n');
}

class FakeNode {
  constructor({tagName = 'DIV', children = [], origin = null} = {}) {
    this.tagName = tagName;
    this.children = children;
    this.childNodes = children;
    this.shadowRoot = null;
    this.parentElement = null;
    this.ownerDocument = null;
    this.isConnected = true;
    this.hidden = false;
    this.disabled = false;
    this.attributes = {};
    this.rect = {left:10,top:10,width:20,height:20};
    this.clickCount = 0;
    for (const child of children) child.parentElement = this;
  }
  getRootNode() { return this._root || this.ownerDocument; }
  matches(selector) { return selector.split(',').some(part => {
    const value=part.trim().toLowerCase(), tag=this.tagName.toLowerCase();
    if(value===tag) return true;
    const match=value.match(/^([a-z][a-z0-9-]*)/);
    if(match&&match[1]!==tag) return false;
    if(value.startsWith('input:not([type="hidden"])')) return tag==='input'&&this.getAttribute('type')!=='hidden';
    if(value.startsWith('[role=')) return this.getAttribute('role')===value.slice(6,-1).replaceAll('"','');
    return false;
  }); }
  hasAttribute(name) { return Object.hasOwn(this.attributes,name); }
  getAttribute(name) { return this.attributes[name] ?? null; }
  getBoundingClientRect() { return this.rect; }
  getClientRects() { return [this.rect]; }
  closest(selector) { for(let node=this;node;node=node.parentElement) if(node.matches(selector)) return node; return null; }
  scrollIntoView() {}
  click() { this.clickCount++; }
  contains(node) { return node === this; }
}

class FakeText {
  constructor(value,parentElement) { this.nodeType=3; this.nodeValue=value; this.parentElement=parentElement; this.ownerDocument=null; }
}

class FakeRoot {
  constructor(document, children = []) {
    this.nodeType = 11;
    this.ownerDocument = document;
    this.children = children;
    for (const child of children) { child.parentElement = null; child._root = this; }
  }
  elementFromPoint() { return this._hit; }
}

class FakeDocument {
  constructor(origin, children = []) {
    this.nodeType = 9;
    this.defaultView = {location:{origin,href:origin+'/'}, document:this, innerWidth:400, innerHeight:800,
      getComputedStyle:() => ({display:'block',visibility:'visible',opacity:'1',contentVisibility:'visible',overflowY:'auto'})};
    this.documentElement = new FakeNode({tagName:'HTML', children});
    this.body = this.documentElement;
    this.documentElement.ownerDocument = this;
    this.assign(this.documentElement);
  }
  assign(node) {
    node.ownerDocument = this;
    for (const child of node.childNodes || []) if(child.nodeType===3) child.ownerDocument=this;
    for (const child of node.children || []) this.assign(child);
    if (node.shadowRoot) for (const child of node.shadowRoot.children) this.assign(child);
  }
  createTreeWalker(root) {
    const nodes = [];
    const walk = node => {
      for (const child of node.children || []) { nodes.push(child); walk(child); }
    };
    walk(root.nodeType === 9 ? root.documentElement : root);
    let index = 0;
    return {nextNode:() => nodes[index++] || null};
  }
  elementFromPoint() { return this._hit; }
}

function attachFrame(element, childDocument, childOrigin) {
  const childWindow = {location:{origin:childOrigin}, document:childDocument};
  element.contentWindow = childWindow;
  Object.defineProperty(element, 'contentDocument', {get() {
    if (childOrigin !== 'https://fixture.test') throw new Error('cross-origin access');
    return childDocument;
  }});
}

function accessibleTraversalRuns() {
  const topButton = new FakeNode({tagName:'BUTTON'});
  const topHost = new FakeNode({tagName:'X-OPEN'});
  const shadowButton = new FakeNode({tagName:'BUTTON'});
  const topFrame = new FakeNode({tagName:'IFRAME'});
  const inaccessibleFrame = new FakeNode({tagName:'IFRAME'});
  const topDoc = new FakeDocument('https://fixture.test', [topButton, topHost, topFrame, inaccessibleFrame]);
  const shadow = new FakeRoot(topDoc, [shadowButton]);
  shadow.host = topHost;
  topHost.shadowRoot = shadow;
  topDoc.assign(shadowButton);

  const childButton = new FakeNode({tagName:'BUTTON'});
  const nestedFrame = new FakeNode({tagName:'IFRAME'});
  const childDoc = new FakeDocument('https://fixture.test', [childButton, nestedFrame]);
  attachFrame(topFrame, childDoc, 'https://fixture.test');

  const nestedButton = new FakeNode({tagName:'BUTTON'});
  const nestedDoc = new FakeDocument('https://fixture.test', [nestedButton]);
  attachFrame(nestedFrame, nestedDoc, 'https://fixture.test');

  const foreignDoc = new FakeDocument('https://other.test', [new FakeNode({tagName:'BUTTON'})]);
  attachFrame(inaccessibleFrame, foreignDoc, 'https://other.test');

  const context = {window:topDoc.defaultView, document:topDoc, Node:{DOCUMENT_NODE:9}, NodeFilter:{SHOW_ELEMENT:1}};
  vm.createContext(context);
  const coverage = {scannedElements:0,frameCount:0,shadowRootCount:0,elementLimitReached:false,
    frameLimitReached:false,shadowLimitReached:false};
  const scopes = [];
  const visited = [];
  vm.runInContext(`${accessibleWalkerSource()}\nwalkAccessible(document,{document,frameWindow:window,frameChain:[],shadowRoots:[],shadowHosts:[]},`+
    `(node, descriptor, frameOrdinal)=>visited.push({node,descriptor,frameOrdinal}),coverage,scopes);`,
    Object.assign(context,{coverage,scopes,visited}));

  assert.deepEqual(visited.map(item => item.node.tagName), ['BUTTON','X-OPEN','BUTTON','IFRAME','BUTTON','IFRAME','BUTTON','IFRAME']);
  assert.equal(coverage.scannedElements, 8);
  assert.equal(coverage.frameCount, 3);
  assert.equal(coverage.shadowRootCount, 1);
  assert.deepEqual(scopes.map(({ordinal,parentOrdinal,depth,status,reason}) => ({ordinal,parentOrdinal,depth,status,reason})), [
    {ordinal:1,parentOrdinal:0,depth:1,status:'accessible',reason:null},
    {ordinal:2,parentOrdinal:1,depth:2,status:'accessible',reason:null},
    {ordinal:3,parentOrdinal:0,depth:1,status:'inaccessible',reason:'CROSS_ORIGIN_OR_OPAQUE'}
  ]);
  assert.equal(visited.find(item => item.node === nestedButton).descriptor.document, nestedDoc);
  assert.equal(visited.find(item => item.node === shadowButton).descriptor.shadowRoots.length, 1);

  const clickPrefix = rawTemplate('private fun actionPrefix(', '""".trimIndent().replace("__NONCE__"')
    .replace('__NONCE__', '"scope_nonce"').replace('$index','0');
  const clickBody = rawTemplate('fun click(', '""".trimIndent().replace("__GATE__"')
    .replaceAll('__GATE__', '"__dshBrowserActionGate"').replaceAll('__AUTHORIZATION_NONCE__', '"auth"');
  const clickScript = clickPrefix + clickBody;
  const target = visited.find(item => item.node === nestedButton);
  const reference = {element:nestedButton,document:nestedDoc,frameWindow:nestedDoc.defaultView,
    frameChain:target.descriptor.frameChain,shadowRoots:target.descriptor.shadowRoots,shadowHosts:target.descriptor.shadowHosts};
  nestedDoc._hit = nestedButton;
  childDoc._hit = nestedFrame;
  topDoc._hit = topFrame;
  const actionWindow = topDoc.defaultView;
  actionWindow.scope_nonce = [reference];
  actionWindow.__dshBrowserActionGate = {authorize:nonce => nonce === 'auth'};
  const actionContext = {window:actionWindow,document:topDoc,Node:{ELEMENT_NODE:1}};
  vm.createContext(actionContext);
  assert.equal(JSON.parse(vm.runInContext(clickScript,actionContext)).code,'OK');
  assert.equal(nestedButton.clickCount,1);

  const replacementDocument = new FakeDocument('https://fixture.test');
  nestedFrame.contentWindow.document = replacementDocument;
  assert.equal(JSON.parse(vm.runInContext(clickScript,actionContext)).code,'FRAME_UNAVAILABLE');
  assert.equal(nestedButton.clickCount,1,'a stale frame document must not receive a second action');

  const locateSource=rawTemplate('fun locate(', '""".trimIndent().replace("__LIMIT__"')
    .replace('__PARAMS__',JSON.stringify({nonce:'locate_nonce',previousNonce:null,strategy:'css',value:'button',exact:true,name:null,scopeNonce:null,scopeIndex:null}))
    .replaceAll('__LIMIT__','5000').replaceAll('__TEXT_LIMIT__','20000').replaceAll('__RELATION_LIMIT__','20000')
    .replaceAll('__ACCESSIBLE_WALKER__',accessibleWalkerSource());
  const uniqueButton=new FakeNode({tagName:'BUTTON'}), hiddenFrame=new FakeNode({tagName:'IFRAME'});
  const locateDoc=new FakeDocument('https://fixture.test',[uniqueButton,hiddenFrame]);
  const foreignLocateDoc=new FakeDocument('https://other.test',[new FakeNode({tagName:'BUTTON'})]);
  attachFrame(hiddenFrame,foreignLocateDoc,'https://other.test');
  const locateContext={window:locateDoc.defaultView,document:locateDoc,location:{href:'https://fixture.test'},
    Node:{DOCUMENT_NODE:9,ELEMENT_NODE:1,TEXT_NODE:3},NodeFilter:{SHOW_ELEMENT:1,SHOW_TEXT:4}};
  vm.createContext(locateContext);
  const locateResult=JSON.parse(vm.runInContext(locateSource,locateContext));
  assert.equal(locateResult.code,'INCOMPLETE_SCOPE','a single observed match must not look unique when a frame is inaccessible');
  assert.equal(locateResult.scopeCoverage.complete,false);
  assert.equal(locateResult.scopeCoverage.inaccessibleFrameCount,1);
  assert.equal(Object.hasOwn(locateResult,'partial'),false);

  const routedTopFrame=new FakeNode({tagName:'IFRAME'});
  const routedHiddenFrame=new FakeNode({tagName:'IFRAME'});
  const routedButton=new FakeNode({tagName:'BUTTON'});
  const routedTopDoc=new FakeDocument('https://fixture.test',[routedTopFrame,routedHiddenFrame]);
  const routedChildDoc=new FakeDocument('https://fixture.test',[routedButton]);
  const routedForeignDoc=new FakeDocument('https://other.test',[new FakeNode({tagName:'BUTTON'})]);
  attachFrame(routedTopFrame,routedChildDoc,'https://fixture.test');
  attachFrame(routedHiddenFrame,routedForeignDoc,'https://other.test');
  const runPartialLocate=(nonce,selector='button',partial=true)=>{
    const script=rawTemplate('fun locate(', '""".trimIndent().replace("__LIMIT__"')
      .replace('__PARAMS__',JSON.stringify({nonce,previousNonce:null,strategy:'css',value:selector,exact:true,name:null,scopeNonce:null,scopeIndex:null,partial}))
      .replaceAll('__LIMIT__','5000').replaceAll('__TEXT_LIMIT__','20000').replaceAll('__RELATION_LIMIT__','20000')
      .replaceAll('__ACCESSIBLE_WALKER__',accessibleWalkerSource());
    const context={window:routedTopDoc.defaultView,document:routedTopDoc,location:routedTopDoc.defaultView.location,
      Node:{DOCUMENT_NODE:9,ELEMENT_NODE:1,TEXT_NODE:3},NodeFilter:{SHOW_ELEMENT:1,SHOW_TEXT:4}};
    vm.createContext(context);
    return {result:JSON.parse(vm.runInContext(script,context)),context,source:script};
  };
  const routedPartial=runPartialLocate('routed_partial');
  assert.equal(routedPartial.source.includes('\"partial\":true'),true);
  assert.equal(routedPartial.result.code,'OK');
  assert.equal(routedPartial.result.partial,true);
  assert.equal(routedPartial.result.scopeCoverage.complete,false);
  assert.equal(routedPartial.result.frameOrdinal,1,'partial success must retain an explicit accessible frame route');
  assert.deepEqual(routedPartial.result.frameScopes.map(scope=>scope.status),['accessible','inaccessible']);
  assert.equal(routedPartial.context.window.routed_partial[0].element,routedButton);

  const missingPartial=runPartialLocate('missing_partial','#missing');
  assert.equal(missingPartial.source.includes('\"partial\":true'),true);
  assert.equal(missingPartial.result.code,'NOT_FOUND');
  assert.equal(missingPartial.result.partial,true);
  assert.equal(missingPartial.result.scopeCoverage.complete,false);
  assert.deepEqual(missingPartial.result.frameScopes.map(scope=>scope.status),['accessible','inaccessible']);
  assert.equal(Object.hasOwn(missingPartial.context.window,'missing_partial'),false);

  const ambiguousHiddenFrame=new FakeNode({tagName:'IFRAME'});
  const ambiguousTopDoc=new FakeDocument('https://fixture.test',
    [new FakeNode({tagName:'BUTTON'}),new FakeNode({tagName:'BUTTON'}),ambiguousHiddenFrame]);
  const ambiguousHiddenDoc=new FakeDocument('https://other.test',[new FakeNode({tagName:'BUTTON'})]);
  attachFrame(ambiguousHiddenFrame,ambiguousHiddenDoc,'https://other.test');
  const ambiguousContext={window:ambiguousTopDoc.defaultView,document:ambiguousTopDoc,location:ambiguousTopDoc.defaultView.location,
    Node:{DOCUMENT_NODE:9,ELEMENT_NODE:1,TEXT_NODE:3},NodeFilter:{SHOW_ELEMENT:1,SHOW_TEXT:4}};
  const ambiguousSource=rawTemplate('fun locate(', '""".trimIndent().replace("__LIMIT__"')
    .replace('__PARAMS__',JSON.stringify({nonce:'ambiguous_partial',previousNonce:null,strategy:'css',value:'button',exact:true,name:null,scopeNonce:null,scopeIndex:null,partial:true}))
    .replaceAll('__LIMIT__','5000').replaceAll('__TEXT_LIMIT__','20000').replaceAll('__RELATION_LIMIT__','20000')
    .replaceAll('__ACCESSIBLE_WALKER__',accessibleWalkerSource());
  vm.createContext(ambiguousContext);
  const ambiguousResult=JSON.parse(vm.runInContext(ambiguousSource,ambiguousContext));
  assert.equal(ambiguousSource.includes('\"partial\":true'),true);
  assert.equal(ambiguousResult.code,'AMBIGUOUS');
  assert.equal(ambiguousResult.partial,true);
  assert.equal(ambiguousResult.scopeCoverage.complete,false);
  assert.equal(Object.hasOwn(ambiguousContext.window,'ambiguous_partial'),false);

  const budgetButton=new FakeNode({tagName:'BUTTON'});
  const budgetElements=Array.from({length:5000},()=>new FakeNode({tagName:'DIV'}));
  const budgetDoc=new FakeDocument('https://fixture.test',[budgetButton,...budgetElements]);
  const budgetContext={window:budgetDoc.defaultView,document:budgetDoc,location:budgetDoc.defaultView.location,
    Node:{DOCUMENT_NODE:9,ELEMENT_NODE:1,TEXT_NODE:3},NodeFilter:{SHOW_ELEMENT:1,SHOW_TEXT:4}};
  const budgetSource=rawTemplate('fun locate(', '""".trimIndent().replace("__LIMIT__"')
    .replace('__PARAMS__',JSON.stringify({nonce:'budget_partial',previousNonce:null,strategy:'css',value:'button',exact:true,name:null,scopeNonce:null,scopeIndex:null,partial:true}))
    .replaceAll('__LIMIT__','5000').replaceAll('__TEXT_LIMIT__','20000').replaceAll('__RELATION_LIMIT__','20000')
    .replaceAll('__ACCESSIBLE_WALKER__',accessibleWalkerSource());
  vm.createContext(budgetContext);
  const budgetResult=JSON.parse(vm.runInContext(budgetSource,budgetContext));
  assert.equal(budgetSource.includes('\"partial\":true'),true);
  assert.equal(budgetResult.code,'LOCATE_LIMIT','a partial unique result must not publish a reference after the element budget is reached');
  assert.equal(budgetResult.partial,true);
  assert.equal(budgetResult.scopeCoverage.complete,false);
  assert.equal(budgetResult.scopeCoverage.elementLimitReached,true);
  assert.equal(Object.hasOwn(budgetContext.window,'budget_partial'),false);

  const textContainers=Array.from({length:11},()=>new FakeNode({tagName:'DIV'}));
  for(const container of textContainers) container.childNodes=Array.from({length:2000},()=>new FakeText('x',container));
  const textBudgetHiddenFrame=new FakeNode({tagName:'IFRAME'});
  const textBudgetDoc=new FakeDocument('https://fixture.test',[...textContainers,textBudgetHiddenFrame]);
  attachFrame(textBudgetHiddenFrame,new FakeDocument('https://other.test',[]),'https://other.test');
  const textBudgetParams={nonce:'text_budget',previousNonce:null,strategy:'text',value:'never-match',exact:true,name:null,scopeNonce:null,scopeIndex:null,partial:true};
  const textBudgetSource=rawTemplate('fun locate(', '""".trimIndent().replace("__LIMIT__"')
    .replace('__PARAMS__',JSON.stringify(textBudgetParams))
    .replaceAll('__LIMIT__','5000').replaceAll('__TEXT_LIMIT__','20000').replaceAll('__RELATION_LIMIT__','20000')
    .replaceAll('__ACCESSIBLE_WALKER__',accessibleWalkerSource());
  const textBudgetContext={window:textBudgetDoc.defaultView,document:textBudgetDoc,location:textBudgetDoc.defaultView.location,
    Node:{DOCUMENT_NODE:9,ELEMENT_NODE:1,TEXT_NODE:3},NodeFilter:{SHOW_ELEMENT:1,SHOW_TEXT:4}};
  vm.createContext(textBudgetContext);
  const textBudgetResult=JSON.parse(vm.runInContext(textBudgetSource,textBudgetContext));
  assert.equal(textBudgetResult.code,'LOCATE_LIMIT');
  assert.equal(textBudgetResult.partial,true);
  assert.equal(textBudgetResult.scopeCoverage.complete,false);
  assert.equal(textBudgetResult.scopeCoverage.textNodesVisited,20000);
  assert.equal(textBudgetResult.scopeCoverage.textLimitReached,true);

  const scopedTopFrame=new FakeNode({tagName:'IFRAME'});
  const scopedTopDoc=new FakeDocument('https://fixture.test',[scopedTopFrame]);
  const scopedChildFrame=new FakeNode({tagName:'IFRAME'});
  const scopeContainer=new FakeNode({tagName:'DIV',children:[scopedChildFrame]});
  const scopedChildDoc=new FakeDocument('https://fixture.test',[scopeContainer]);
  const scopedInnerButton=new FakeNode({tagName:'BUTTON'});
  const scopedInnerDoc=new FakeDocument('https://fixture.test',[scopedInnerButton]);
  attachFrame(scopedTopFrame,scopedChildDoc,'https://fixture.test');
  attachFrame(scopedChildFrame,scopedInnerDoc,'https://fixture.test');
  const scopedRef={element:scopeContainer,document:scopedChildDoc,frameWindow:scopedChildDoc.defaultView,
    frameChain:[{element:scopedTopFrame,parentDocument:scopedTopDoc,parentShadowRoots:[],parentShadowHosts:[],
      childWindow:scopedTopFrame.contentWindow,childDocument:scopedChildDoc}],shadowRoots:[],shadowHosts:[],frameOrdinal:1};
  const scopedLocateSource=rawTemplate('fun locate(', '""".trimIndent().replace("__LIMIT__"')
    .replace('__PARAMS__',JSON.stringify({nonce:'scoped_result',previousNonce:null,strategy:'css',value:'button',exact:true,name:null,scopeNonce:'scope_nonce',scopeIndex:0}))
    .replaceAll('__LIMIT__','5000').replaceAll('__TEXT_LIMIT__','20000').replaceAll('__RELATION_LIMIT__','20000')
    .replaceAll('__ACCESSIBLE_WALKER__',accessibleWalkerSource());
  const scopedContext={window:scopedTopDoc.defaultView,document:scopedTopDoc,location:scopedTopDoc.defaultView.location,
    Node:{DOCUMENT_NODE:9,ELEMENT_NODE:1,TEXT_NODE:3},NodeFilter:{SHOW_ELEMENT:1,SHOW_TEXT:4}};
  scopedContext.window.scope_nonce=[scopedRef];
  vm.createContext(scopedContext);
  const scopedResult=JSON.parse(vm.runInContext(scopedLocateSource,scopedContext));
  assert.equal(scopedResult.code,'OK');
  assert.equal(scopedResult.frameOrdinal,2,'a frame-scoped result must retain its local iframe path');
  assert.deepEqual(scopedResult.frameScopes.map(scope=>scope.ordinal),[1,2]);
  assert.equal(scopedResult.scopeCoverage.frameCount,2);
  assert.equal(scopedResult.frameScopes[1].parentOrdinal,1);

  const observeSource=rawTemplate('fun observe(', '""".trimIndent().replace("__NONCE__"')
    .replaceAll('__NONCE__','"observe_nonce"').replaceAll('__PREVIOUS_NONCE__','null')
    .replaceAll('$maxTextChars','4096').replaceAll('__ACCESSIBLE_WALKER__',accessibleWalkerSource())
    .replaceAll('__OVERLAY_OBSERVATION__',overlayScript);
  const labelledButton=new FakeNode({tagName:'BUTTON'});
  labelledButton.attributes['aria-label']='Continue';
  const placeholderInput=new FakeNode({tagName:'INPUT'});
  placeholderInput.attributes.type='search';
  placeholderInput.attributes.placeholder='Search products';
  const privateInput=new FakeNode({tagName:'INPUT'});
  privateInput.attributes.type='password'; privateInput.attributes.value='secret-value-must-not-escape';
  const observeDoc=new FakeDocument('https://fixture.test',[labelledButton,placeholderInput,privateInput]);
  observeDoc.title='Fixture'; observeDoc.readyState='complete';
  const observeContext={window:observeDoc.defaultView,document:observeDoc,location:observeDoc.defaultView.location,
    Node:{DOCUMENT_NODE:9,ELEMENT_NODE:1,TEXT_NODE:3},NodeFilter:{SHOW_ELEMENT:1,SHOW_TEXT:4}};
  vm.createContext(observeContext);
  const observeResult=JSON.parse(vm.runInContext(observeSource,observeContext));
  assert.equal(observeResult.elements[0].label,'Continue');
  assert.equal(observeResult.elements[0].accessibleName,'Continue');
  assert.equal(observeResult.elements[0].placeholder,'');
  assert.equal(observeResult.elements[1].accessibleName,'');
  assert.equal(observeResult.elements[1].placeholder,'Search products');
  assert.equal(observeResult.elements[0].frameOrdinal,0);
  assert.equal(observeResult.elements[0].shadowDepth,0);
  assert.equal(observeResult.scopeCoverage.complete,true);
  assert.equal(observeResult.openShadowRootsSupported,true);
  assert.equal(observeResult.topFrameOnly,false);
  assert.equal(observeResult.closedShadowRootsSupported,false);
  assert.equal(observeResult.crossOriginFramesSupported,false);
  assert.equal(Object.keys(observeDoc.defaultView).includes('observe_nonce'),false,
    'the private registry must not appear among enumerable page globals');
  assert.equal(JSON.stringify(observeResult).includes('secret-value-must-not-escape'),false,
    'observe must not serialize private input values');

  const observationGeneration=7;
  const observationData={...observeResult,title:'Fixture',url:'https://fixture.test/',urlQueryRedacted:false,
    urlFragmentRedacted:false,loadState:'finished',observationGeneration,
    elements:observeResult.elements.map((element,index)=>({
      nodeId:`b:4:9:3:${observationGeneration}:0123456789abcdef0123456789abcdef:${index}`,
      tag:element.tag,type:element.type,role:element.role,label:element.label,
      accessibleName:element.accessibleName,placeholder:element.placeholder,href:element.href,
      hrefQueryRedacted:false,hrefFragmentRedacted:false,frameOrdinal:element.frameOrdinal,shadowDepth:element.shadowDepth,
    }))};
  const overlayResult=observationData.overlayObservation;
  delete observationData.overlayObservation;
  if(overlayResult) {
    observationData.overlays=overlayResult.overlays;
    observationData.overlayScope=overlayResult.scope;
    observationData.overlayCoverage=overlayResult.coverage;
  }
  const observeWire={version:1,requestId:'real-dom-observe',code:'OK',executed:true,sessionGeneration:4,
    controlGeneration:9,state:'RUNNING',owner:'ai',data:observationData};
  const normalizedObserve=normalizeResponse('observe','real-dom-observe',observeWire);
  assert.ok(normalizedObserve,'real DOM producer output must satisfy the bridge CLI contract');
  assert.deepEqual(normalizedObserve.data.elements.map(element=>[element.accessibleName,element.placeholder]),
    [['Continue',''],['','Search products'],['','']]);
  assert.ok(safeResponse(`${JSON.stringify(normalizedObserve)}\n`,'real-dom-observe','observe'),
    'the plugin client must accept the same real producer response as the bridge CLI');
  const overlayData=JSON.parse(JSON.stringify(observationData));
  const overlayBox={x:0,y:0,width:100,height:100};
  overlayData.overlays=[{kind:'modal',tag:'div',role:'dialog',name:'Dialog',box:overlayBox,
    viewportBox:overlayBox,viewportCoverageRatio:0.5,
    evidence:{fixed:true,sticky:false,dialog:false,modal:true,zIndex:100,semanticHint:'modal'},
    occlusion:{sampled:1,covered:1,ratio:1},pseudo:[{source:'before',boundedContent:'Prompt'}],
    dismiss:{status:'available',candidates:[{nodeId:overlayData.elements[0].nodeId,tag:'button',role:'button',name:'Close',box:overlayBox}]}}];
  overlayData.overlayScope={topFrameOnly:true,includesCrossOriginFrames:false,frameCoverage:'not_inspected',complete:false};
  overlayData.overlayCoverage={partial:false,candidateScan:1,candidateScanLimit:256,candidateLimitReached:false,
    viewportSampleCount:2,viewportSampleLimit:25,overlayOutputCount:1,overlayOutputLimit:8,outputTruncated:false,
    textNodesVisited:0,textChars:0,textLimitReached:false,errors:[]};
  const overlayWire={...observeWire,data:overlayData};
  assert.ok(normalizeResponse('observe','real-dom-observe',overlayWire));
  assert.ok(safeResponse(JSON.stringify(overlayWire)+'\n','real-dom-observe','observe'));
  for(const mutate of [
    d=>d.overlays[0].dismiss.candidates[0].nodeId='b:orphan',
    d=>d.overlays[0].dismiss.candidates[0].refIndex=0,
    d=>d.elements[0].frameOrdinal=1,
    d=>d.overlayScope.complete=true,
    d=>d.overlayCoverage.viewportSampleCount=26,
    d=>delete d.overlayScope,
    d=>d.overlays[0].occlusion.ratio=0,
    d=>d.overlays[0].backendDOMNodeId=71,
  ]) {
    const invalid=JSON.parse(JSON.stringify(overlayData)); mutate(invalid);
    const wire={...observeWire,data:invalid};
    assert.equal(normalizeResponse('observe','real-dom-observe',wire),null);
    assert.equal(safeResponse(JSON.stringify(wire)+'\n','real-dom-observe','observe'),null);
  }
  process.stdout.write('OVERLAY_CONTRACT_OK\n');
  for(const signals of [[],[{kind:'verification',source:'overlay',evidence:'keyword_match'}],
    [{kind:'authentication',source:'elements',evidence:'password_field_present'}]]) {
    const data=JSON.parse(JSON.stringify(observationData));
    data.blockInfo={assessment:signals.length?'possible':'not_detected',signals,automaticResolutionAllowed:false,
      nextAction:signals.length?'inspect_and_request_user_if_needed':'continue_observing'};
    const wire={...observeWire,data};
    assert.ok(normalizeResponse('observe','real-dom-observe',wire));
    assert.ok(safeResponse(JSON.stringify(wire)+'\n','real-dom-observe','observe'));
    for(const mutate of [b=>b.assessment='blocked',b=>b.automaticResolutionAllowed=true,
      b=>b.nextAction='solve_captcha',b=>b.rawPassword='secret',
      b=>b.signals=[{kind:'authentication',source:'text',evidence:'keyword_match'}]]) {
      const invalid=JSON.parse(JSON.stringify(data)); mutate(invalid.blockInfo);
      assert.equal(normalizeResponse('observe','real-dom-observe',{...wire,data:invalid}),null);
      assert.equal(safeResponse(JSON.stringify({...wire,data:invalid})+'\n','real-dom-observe','observe'),null);
    }
  }
  process.stdout.write('BLOCK_EVIDENCE_CONTRACT_OK\n');

  const partialFrame=new FakeNode({tagName:'IFRAME'});
  const partialButton=new FakeNode({tagName:'BUTTON'});
  partialButton.attributes['aria-label']='Available action';
  const partialDoc=new FakeDocument('https://fixture.test',[partialButton,partialFrame]);
  partialDoc.title='Partial fixture'; partialDoc.readyState='complete';
  attachFrame(partialFrame,new FakeDocument('https://other.test',[new FakeNode({tagName:'BUTTON'})]),'https://other.test');
  const partialContext={window:partialDoc.defaultView,document:partialDoc,location:partialDoc.defaultView.location,
    Node:{DOCUMENT_NODE:9,ELEMENT_NODE:1,TEXT_NODE:3},NodeFilter:{SHOW_ELEMENT:1,SHOW_TEXT:4}};
  vm.createContext(partialContext);
  const partialResult=JSON.parse(vm.runInContext(observeSource,partialContext));
  assert.equal(partialResult.scopeCoverage.complete,false);
  assert.equal(partialResult.scopeCoverage.inaccessibleFrameCount,1);
  assert.equal(partialResult.elements.length,1,'an inaccessible frame must not discard accessible partial-scope nodes');
  assert.equal(windowRef(partialContext.window,'observe_nonce').element,partialButton,
    'accessible partial-scope nodes retain actionable references');

  const locatedInput=new FakeNode({tagName:'INPUT'});
  locatedInput.attributes.type='search'; locatedInput.attributes.placeholder='Search terms';
  const nameDoc=new FakeDocument('https://fixture.test',[locatedInput]);
  const locateWith=(strategy,name,value)=>{
    const script=rawTemplate('fun locate(', '""".trimIndent().replace("__LIMIT__"')
      .replace('__PARAMS__',JSON.stringify({nonce:'semantic_'+strategy,previousNonce:null,strategy,value,exact:true,name,scopeNonce:null,scopeIndex:null}))
      .replaceAll('__LIMIT__','5000').replaceAll('__TEXT_LIMIT__','20000').replaceAll('__RELATION_LIMIT__','20000')
      .replaceAll('__ACCESSIBLE_WALKER__',accessibleWalkerSource());
    const context={window:nameDoc.defaultView,document:nameDoc,location:nameDoc.defaultView.location,
      Node:{DOCUMENT_NODE:9,ELEMENT_NODE:1,TEXT_NODE:3},NodeFilter:{SHOW_ELEMENT:1,SHOW_TEXT:4}};
    vm.createContext(context); return JSON.parse(vm.runInContext(script,context));
  };
  assert.equal(locateWith('role','Search terms','searchbox').code,'NOT_FOUND',
    'placeholder text must not be treated as an accessible name');
  assert.equal(locateWith('placeholder',null,'Search terms').code,'OK',
    'placeholder strategy must match the explicit placeholder attribute');

  class FakeInput extends FakeNode {
    constructor() { super({tagName:'INPUT'}); this.type='text'; this.value=''; this.disabled=false; this.readOnly=false; this.isContentEditable=false; }
    focus() { this.ownerDocument.defaultView.activeElement=this; }
    dispatchEvent() { return true; }
  }
  Object.defineProperty(FakeInput.prototype,'value',{get(){return this._value||'';},set(value){this._value=value;}});
  class FakeInputEvent { constructor(type,init){this.type=type;Object.assign(this,init);} }
  class FakeEvent { constructor(type,init){this.type=type;Object.assign(this,init);} }
  const typePrefix=rawTemplate('private fun actionPrefix(', '""".trimIndent().replace("__NONCE__"')
    .replace('__NONCE__','"type_nonce"').replace('$index','0');
  const typeBody=rawTemplate('fun type(', '""".trimIndent().replace("__GATE__"')
    .replaceAll('__GATE__','"__dshBrowserActionGate"').replaceAll('__AUTHORIZATION_NONCE__','"auth"')
    .replace('$quoted','"value"').replace('$valueExpression','value').replace('$contentExpression','value');
  const runType=hit=>{
    const input=new FakeInput(), doc=new FakeDocument('https://fixture.test',[input]);
    input.ownerDocument=doc; doc.defaultView.HTMLInputElement=FakeInput; doc.defaultView.HTMLTextAreaElement=class {};
    doc.defaultView.InputEvent=FakeInputEvent; doc.defaultView.Event=FakeEvent; doc.defaultView.activeElement=null;
    doc._hit=hit(input,doc); const ref={element:input,document:doc,frameWindow:doc.defaultView,frameChain:[],shadowRoots:[],shadowHosts:[]};
    doc.defaultView.type_nonce=[ref]; doc.defaultView.__dshBrowserActionGate={authorize:nonce=>nonce==='auth'};
    const context={window:doc.defaultView,document:doc,Node:{ELEMENT_NODE:1}}; vm.createContext(context);
    return {result:JSON.parse(vm.runInContext(typePrefix+typeBody,context)),input};
  };
  const labelled=runType((input,doc)=>{const label=new FakeNode({tagName:'LABEL'});label.ownerDocument=doc;label.control=input;input.labels=[label];return label;});
  assert.equal(labelled.result.code,'OK','a directly associated label is a valid hit target for typing');
  assert.equal(labelled.input.value,'value');
  const overlay=runType(()=>{const node=new FakeNode({tagName:'DIV'});node.id='modal-shield';node.className='modal shield';return node;});
  assert.equal(overlay.result.code,'OBSCURED','an unrelated hit target still blocks typing');
  assert.deepEqual(overlay.result.obscuredBy,{tag:'div',id:'modal-shield',className:'modal shield'});

  const hiddenObserveFrame=new FakeNode({tagName:'IFRAME'});
  const hiddenObserveButton=new FakeNode({tagName:'BUTTON'});
  hiddenObserveButton.attributes['aria-label']='Hidden frame target';
  const hiddenObserveDoc=new FakeDocument('https://fixture.test',[hiddenObserveFrame]);
  const hiddenObserveChild=new FakeDocument('https://fixture.test',[hiddenObserveButton]);
  const hiddenText=new FakeText('hidden frame text',hiddenObserveButton);
  hiddenText.ownerDocument=hiddenObserveChild;
  const hiddenWalker=hiddenObserveChild.createTreeWalker.bind(hiddenObserveChild);
  hiddenObserveChild.createTreeWalker=(root,kind)=>{
    if(kind!==4) return hiddenWalker(root,kind);
    let visited=false;
    return {nextNode:()=>visited?null:(visited=true,hiddenText)};
  };
  attachFrame(hiddenObserveFrame,hiddenObserveChild,'https://fixture.test');
  hiddenObserveDoc.title='Fixture'; hiddenObserveDoc.readyState='complete';
  hiddenObserveDoc.defaultView.getComputedStyle=element=>({display:'block',visibility:'visible',
    opacity:element===hiddenObserveFrame?'0':'1',contentVisibility:'visible'});
  const hiddenObserveContext={window:hiddenObserveDoc.defaultView,document:hiddenObserveDoc,
    location:hiddenObserveDoc.defaultView.location,Node:{DOCUMENT_NODE:9,ELEMENT_NODE:1,TEXT_NODE:3},
    NodeFilter:{SHOW_ELEMENT:1,SHOW_TEXT:4}};
  vm.createContext(hiddenObserveContext);
  const hiddenObserveResult=JSON.parse(vm.runInContext(observeSource,hiddenObserveContext));
  assert.equal(hiddenObserveResult.elements.some(element=>element.label==='Hidden frame target'),false,
    'invisible frame ancestry must not produce an observed actionable element');
  assert.equal(hiddenObserveResult.text.includes('hidden frame text'),false,
    'invisible frame ancestry must not contribute visible text');

  const runWalker=doc=>{
    const ctx={window:doc.defaultView,document:doc,Node:{DOCUMENT_NODE:9},NodeFilter:{SHOW_ELEMENT:1}};
    vm.createContext(ctx);
    const coverage={scannedElements:0,frameCount:0,shadowRootCount:0,inaccessibleFrameCount:0,
      elementLimitReached:false,frameLimitReached:false,shadowLimitReached:false};
    const scopes=[],visited=[];
    vm.runInContext(`${accessibleWalkerSource()}\nwalkAccessible(document,{document,frameWindow:window,frameChain:[],shadowRoots:[],shadowHosts:[]},`+
      `(node,descriptor,frameOrdinal)=>visited.push({node,descriptor,frameOrdinal}),coverage,scopes);`,
      Object.assign(ctx,{coverage,scopes,visited}));
    return {coverage,scopes,visited};
  };
  const wideDoc=new FakeDocument('https://fixture.test',Array.from({length:5001},()=>new FakeNode({tagName:'DIV'})));
  const wide=runWalker(wideDoc);
  assert.equal(wide.coverage.scannedElements,5000);
  assert.equal(wide.coverage.elementLimitReached,true,'the walker must stop at the global element budget');
  const frameNodes=Array.from({length:17},()=>new FakeNode({tagName:'IFRAME'}));
  const frameDoc=new FakeDocument('https://fixture.test',frameNodes);
  const sharedChild=new FakeDocument('https://fixture.test');
  for(const frame of frameNodes) attachFrame(frame,sharedChild,'https://fixture.test');
  const framed=runWalker(frameDoc);
  assert.equal(framed.coverage.frameCount,16);
  assert.equal(framed.scopes.length,16);
  assert.equal(framed.coverage.frameLimitReached,true);
  const shadowHosts=Array.from({length:33},()=>new FakeNode({tagName:'X-HOST'}));
  const shadowDoc=new FakeDocument('https://fixture.test',shadowHosts);
  for(const host of shadowHosts) {
    const root=new FakeRoot(shadowDoc,[new FakeNode({tagName:'BUTTON'})]);
    root.host=host; host.shadowRoot=root; shadowDoc.assign(root.children[0]);
  }
  const shadowed=runWalker(shadowDoc);
  assert.equal(shadowed.coverage.shadowRootCount,32);
  assert.equal(shadowed.coverage.shadowLimitReached,true,'the walker must stop at the global open-shadow budget');
  process.stdout.write('ACCESSIBLE_SCOPE_OK\n');
}

extractedScriptsParse();
accessibleTraversalRuns();

function collectWindowRuns() {
  const params={containerSelector:'#messages',messageSelector:'.message',keyAttribute:'data-id',
    authorSelector:'.author',authorAttribute:'',contentSelector:'.body',orderAttribute:'data-order',
    totalCountSelector:'#total',totalCountAttribute:'',maxRecords:20,maxContentChars:100};
  const script=rawTemplate('fun collectWindow(', '""".trimIndent().replace("__PARAMS__"')
    .replace('__PARAMS__',JSON.stringify(params)).replace('__NONCE__','"collect_nonce"')
    .replace('__PREVIOUS_NONCE__','"old_collect_nonce"');
  const makeElement=(tag,attrs={},children=[],style={})=>{
    const node={nodeType:1,tagName:tag.toUpperCase(),attributes:attrs,children,childNodes:children,style,
      parentElement:null,parentNode:null,ownerDocument:null,isConnected:true,hidden:Object.hasOwn(attrs,'hidden'),
      isContentEditable:Object.hasOwn(attrs,'contenteditable'),clientHeight:100,scrollHeight:300,scrollTop:0,
      getAttribute(name){return this.attributes[name]??null;},hasAttribute(name){return Object.hasOwn(this.attributes,name);},
      matches(selector){return selector.startsWith('#')?this.getAttribute('id')===selector.slice(1):
        selector.startsWith('.')?String(this.getAttribute('class')||'').split(/\s+/).includes(selector.slice(1)):
        selector.toLowerCase()===this.tagName.toLowerCase();},
      querySelector(selector){const walker=this.ownerDocument.createTreeWalker(this,1);let item;
        while((item=walker.nextNode())) if(item.matches(selector)) return item; return null;},
      getBoundingClientRect(){return {width:100,height:20};},getClientRects(){return [1];}};
    for(const child of children){child.parentElement=node;child.parentNode=node;}
    return node;
  };
  const text=(value,parent)=>({nodeType:3,nodeValue:value,parentElement:parent,parentNode:parent,ownerDocument:null});
  const author=makeElement('span',{class:'author'},[]); author.childNodes=[text('Alice',author)]; author.children=[];
  const nestedHidden=makeElement('span',{'aria-hidden':'true'},[]); nestedHidden.childNodes=[text(' hidden leak',nestedHidden)];
  const editable=makeElement('div',{contenteditable:'true'},[]); editable.childNodes=[text(' DRAFT_LEAK',editable)];
  const textbox=makeElement('div',{role:'textbox'},[]); textbox.childNodes=[text(' TEXTBOX_LEAK',textbox)];
  const invisible=makeElement('span',{},[] ,{display:'none'}); invisible.childNodes=[text(' CSS_HIDDEN_LEAK',invisible)];
  const draftMarker=makeElement('span',{class:'draft'},[]);
  const body=makeElement('div',{class:'body'},[text('Hello virtual list',null),nestedHidden,editable,textbox,invisible]);
  const item=makeElement('article',{class:'message','data-id':'msg-1','data-order':'0'},[author,body,draftMarker]);
  const ariaHidden=makeElement('article',{class:'message','data-id':'hidden','data-order':'1','aria-hidden':'true'},
    [makeElement('div',{class:'author'},[]),makeElement('div',{class:'body'},[])]);
  const hiddenAncestor=makeElement('section',{},[makeElement('article',{class:'message','data-id':'css-hidden','data-order':'2'},
    [makeElement('div',{class:'author'},[]),makeElement('div',{class:'body'},[])])],{visibility:'hidden'});
  const total=makeElement('output',{id:'total'},[]); total.childNodes=[text('1',total)];
  const container=makeElement('div',{id:'messages'},[item,ariaHidden,hiddenAncestor]);
  const html=makeElement('html',{},[container,total]);
  const document={nodeType:9,documentElement:html,childNodes:[html],defaultView:null,querySelector(selector){return selector==='#total'?total:null;},
    createTreeWalker(root,show){
      const nodes=[];const visit=node=>{for(const child of node.childNodes||[]){if(show===1?child.nodeType===1:child.nodeType===3)nodes.push(child);visit(child);}};visit(root);
      let index=0;return {nextNode(){return nodes[index++]||null;}};}};
  const attach=node=>{node.ownerDocument=document;for(const child of node.childNodes||[]){child.ownerDocument=document;attach(child);}};attach(html);
  document.defaultView={getComputedStyle:node=>({...{display:'block',visibility:'visible',contentVisibility:'visible',opacity:'1',overflowY:node===container?'auto':'visible'},...node.style})};
  const context={document,window:document.defaultView,NodeFilter:{SHOW_ELEMENT:1,SHOW_TEXT:4},getComputedStyle:document.defaultView.getComputedStyle};
  context.window.old_collect_nonce=[{element:{}}];
  vm.createContext(context);
  const run=()=>JSON.parse(vm.runInContext(script,context));
  const result=run();
  assert.equal(result.code,'OK');
  assert.deepEqual(result.records,[{key:'msg-1',author:'Alice',content:'Hello virtual list',order:0}]);
  const serialized=JSON.stringify(result);
  for(const secret of ['DRAFT_LEAK','TEXTBOX_LEAK','CSS_HIDDEN_LEAK','hidden leak']) assert.equal(serialized.includes(secret),false);
  assert.equal(result.container.startReached,true);
  assert.equal(result.container.endReached,false);
  assert.equal(result.containerRefIndex,0);
  assert.equal(Object.hasOwn(context.window,'old_collect_nonce'),false,'collect must revoke the prior observe registry');
  const ref=windowRef(context.window,'collect_nonce');
  assert.equal(ref.element,container);
  assert.equal(ref.document,document);

  const excludeScript=rawTemplate('fun collectWindow(', '""".trimIndent().replace("__PARAMS__"')
    .replace('__PARAMS__',JSON.stringify({...params,excludeSelector:'.draft'})).replace('__NONCE__','"collect_exclude_nonce"')
    .replace('__PREVIOUS_NONCE__','null');
  const excluded=JSON.parse(vm.runInContext(excludeScript,context));
  assert.equal(excluded.code,'OK');
  assert.deepEqual(excluded.records,[]);
  assert.equal(excluded.excludedCount,1);
  for(const secret of ['msg-1','Alice','Hello virtual list']) assert.equal(JSON.stringify(excluded).includes(secret),false);

  const collectBridge=require('../../../../browser-bridge/src/main/assets/browser/lib/browser-collect.cjs');
  let observation=0, activeNodeId='';
  const nativeEnvelope=(raw)=>{
    assert.equal(raw.code,'OK');
    const identity={pageId:'pg-real-dom-fixture',documentGeneration:5,navigationId:9,observationId:++observation,
      sessionGeneration:12,controlGeneration:4};
    activeNodeId=`b:12:4:5:${observation}:fixture:${0}`;
    return {code:'OK',data:{...raw,action:'window',declaredRange:'current conversation message list',
      identity,containerNodeId:activeNodeId}};
  };
  const invokeBrowser=async(method,args)=>{
    if(method==='collect') return nativeEnvelope(run());
    assert.equal(method,'scroll');
    assert.equal(args.nodeId,activeNodeId,'native adapter must issue and consume the current observation ref');
    container.scrollTop=200;
    return {code:'OK',data:{code:'OK'}};
  };
  return collectBridge.collectVirtualizedWindow({invokeBrowser,options:{...params,fromTop:true,
    declaredRange:'current conversation message list'}}).then(result=>{
    assert.equal(result.complete,true,JSON.stringify(result));
    assert.equal(result.scope,'current conversation message list');
    assert.deepEqual(result.records,[{key:'msg-1',author:'Alice',content:'Hello virtual list',order:0}]);
    process.stdout.write('COLLECT_WINDOW_OK\n');
  });
}

function windowRef(pageWindow, nonce) {
  const entries=pageWindow[nonce];
  assert.ok(Array.isArray(entries),'container reference must use the observe window registry');
  return entries[0];
}

collectWindowRuns().catch(error=>{process.stderr.write(`${error.stack}\n`);process.exitCode=1;});
