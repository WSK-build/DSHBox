package com.dshbox.browser.runtime

import org.json.JSONObject

/** Builds fixed scripts and encodes caller values as JSON data; callers cannot provide source code. */
internal object BrowserDomScript {
    const val ACTION_GATE_NAME = "__dshBrowserActionGate"
    const val LOCATE_SCAN_LIMIT = 5000
    const val LOCATE_TEXT_NODE_LIMIT = 20_000

    private fun accessibleWalker(): String = """
      const walkAccessible = (start, startDescriptor, onElement, coverage, scopes) => {
        const visit = (root, descriptor, frameOrdinal, frameDepth) => {
          const doc = descriptor.document;
          if (coverage._textRoots) {
            const textRoot=root.nodeType===Node.DOCUMENT_NODE?(root.body||root.documentElement):root;
            if(textRoot) coverage._textRoots.push({root:textRoot,descriptor});
          }
          const walker = doc.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
          let element;
          while (!coverage.stopTraversal && (element = walker.nextNode())) {
            if (coverage.scannedElements >= 5000) {
              coverage.elementLimitReached = true;
              coverage.stopTraversal = true;
              return;
            }
            coverage.scannedElements++;
            if (onElement(element, descriptor, frameOrdinal) === false) return;
            let shadow = null;
            try { shadow = element.shadowRoot; } catch (_) {}
            if (shadow) {
              if (coverage.shadowRootCount >= 32) coverage.shadowLimitReached = true;
              else {
                coverage.shadowRootCount++;
                visit(shadow, {...descriptor, shadowRoots:descriptor.shadowRoots.concat([shadow]),
                  shadowHosts:descriptor.shadowHosts.concat([element])}, frameOrdinal, frameDepth);
              }
            }
            if (element.tagName === 'IFRAME' || element.tagName === 'FRAME') {
              if (coverage.frameCount >= 16 || frameDepth >= 4) {
                coverage.frameLimitReached = true;
                continue;
              }
              coverage.frameCount++;
              const ordinal = coverage.frameCount;
              const scope = {ordinal,parentOrdinal:frameOrdinal,depth:frameDepth+1,
                status:'inaccessible',reason:'CROSS_ORIGIN_OR_OPAQUE'};
              scopes.push(scope);
              try {
                const childWindow=element.contentWindow, childDocument=element.contentDocument;
                if (!childWindow || !childDocument || childWindow.document!==childDocument ||
                    childWindow.location.origin==='null' || childWindow.location.origin!==window.location.origin) continue;
                scope.status='accessible'; scope.reason=null;
                visit(childDocument,{document:childDocument,frameWindow:childWindow,frameOrdinal:ordinal,
                  frameChain:descriptor.frameChain.concat([{element,parentDocument:descriptor.document,
                    parentShadowRoots:descriptor.shadowRoots,parentShadowHosts:descriptor.shadowHosts,childWindow,childDocument}]),
                  shadowRoots:[],shadowHosts:[]},ordinal,frameDepth+1);
              } catch (_) {}
            }
          }
        };
        visit(start,startDescriptor,startDescriptor.frameOrdinal||0,startDescriptor.frameChain.length);
      };
    """.trimIndent()
    fun locate(
        nonce: String,
        previousNonce: String?,
        strategy: String,
        value: String,
        exact: Boolean,
        name: String?,
        scopeNonce: String?,
        scopeIndex: Int?,
        partial: Boolean = false,
    ): String {
        val params = JSONObject().put("nonce", nonce).put("previousNonce", previousNonce ?: JSONObject.NULL)
            .put("strategy", strategy).put("value", value).put("exact", exact)
            .put("name", name ?: JSONObject.NULL).put("scopeNonce", scopeNonce ?: JSONObject.NULL)
            .put("scopeIndex", scopeIndex ?: JSONObject.NULL).put("partial", partial)
        return """
            (() => {
              const p = __PARAMS__;
              __ACCESSIBLE_WALKER__
              const norm = s => String(s || '').replace(/\s+/g, ' ').trim();
              const textMatch = (actual, expected) => p.exact ? norm(actual) === norm(expected) : norm(actual).includes(norm(expected));
              let textNodesVisited=0, textLimitExceeded=false;
              const boundedName = value => { const result=norm(value); if(result.length>2048) textLimitExceeded=true; return result.slice(0,2048); };
              const attributeMatch = (actual, expected) => {
                const value=String(actual||''); if(value.length>2048) { textLimitExceeded=true; return false; }
                return textMatch(value,expected);
              };
              const privateSelector = 'input,textarea,select,[contenteditable],[role="textbox"],[role="searchbox"],[role="combobox"],[role="spinbutton"],[role="slider"]';
              const safeText = root => {
                if (!root || root.nodeType !== Node.ELEMENT_NODE || root.matches(privateSelector)) return '';
                const doc=root.ownerDocument, walker=doc.createTreeWalker(root,NodeFilter.SHOW_TEXT), parts=[]; let node, used=0, visited=0;
                while((node=walker.nextNode())) {
                  if(++visited>5000 || used>=2048) { textLimitExceeded=true; coverage.textLimitReached=true; break; }
                  if(textNodesVisited>=__TEXT_LIMIT__) { textLimitExceeded=true; coverage.textLimitReached=true; break; }
                  textNodesVisited++;
                  coverage.textNodesVisited=textNodesVisited;
                  let e=node.parentElement, allowed=true;
                  while(e) {
                    if(e.matches(privateSelector+',script,style,noscript,template,[hidden],[aria-hidden="true"]')) { allowed=false; break; }
                    const s=(e.ownerDocument.defaultView||window).getComputedStyle(e);
                    if(s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse'||s.contentVisibility==='hidden'||Number(s.opacity)===0) { allowed=false; break; }
                    if(e.parentElement) e=e.parentElement;
                    else { const root=e.getRootNode(); e=root&&root.host?root.host:null; }
                  }
                  if(allowed) { const part=(node.nodeValue||'').slice(0,2048-used); parts.push(part); used+=part.length; }
                }
                return norm(parts.join(' ')).slice(0,2048);
              };
              const directText = root => {
                const parts=[]; let used=0;
                for(const node of root.childNodes) {
                  if(node.nodeType!==Node.TEXT_NODE) continue;
                  if(textNodesVisited>=__TEXT_LIMIT__) { textLimitExceeded=true; coverage.textLimitReached=true; break; }
                  textNodesVisited++;
                  coverage.textNodesVisited=textNodesVisited;
                  const parent=node.parentElement, style=parent?(parent.ownerDocument.defaultView||window).getComputedStyle(parent):null;
                  if(!parent || parent.matches(privateSelector+',script,style,noscript,template,[hidden],[aria-hidden="true"]') ||
                    style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse'||style.contentVisibility==='hidden'||Number(style.opacity)===0) continue;
                  const value=node.nodeValue||'';
                  if(value.length>2048-used) { textLimitExceeded=true; coverage.textLimitReached=true; parts.push(value.slice(0,2048-used)); used=2048; break; }
                  parts.push(value); used+=value.length;
                }
                return norm(parts.join(' ')).slice(0,2048);
              };
              const actionable=e=>e.matches('a[href],button,input:not([type="hidden"]),textarea,select,summary,[onclick],[tabindex]:not([tabindex="-1"]),[role="button"],[role="link"],[role="checkbox"],[role="radio"],[role="tab"],[role="menuitem"]');
              const visible = e => {
                if (!e || !e.isConnected || e.hidden || e.getAttribute('aria-hidden') === 'true') return false;
                for (let n=e; n;) {
                  const s=(n.ownerDocument.defaultView||window).getComputedStyle(n);
                  if (s.display==='none' || s.visibility==='hidden' || s.visibility==='collapse' || s.contentVisibility==='hidden' || Number(s.opacity)===0 || n.getAttribute('aria-hidden')==='true') return false;
                  if(n.parentElement) n=n.parentElement;
                  else { const root=n.getRootNode(); n=root&&root.host?root.host:null; }
                }
                const r=e.getBoundingClientRect(); return r.width>0 && r.height>0 && e.getClientRects().length>0;
              };
              const nameOf = e => {
                const doc=e.ownerDocument, labelledby=(e.getAttribute('aria-labelledby')||'').trim().split(/\s+/).filter(Boolean);
                if (labelledby.length) {
                  const v=labelledby.map(id=>doc.getElementById(id)).filter(Boolean).map(safeText).join(' ');
                  if (norm(v)) return boundedName(v);
                }
                const aria=e.getAttribute('aria-label'); if (norm(aria)) return boundedName(aria);
                if (e.labels && e.labels.length) {
                  const v=Array.from(e.labels).map(safeText).join(' '); if(norm(v)) return boundedName(v);
                }
                const nested=e.closest('label'); if(nested && norm(safeText(nested))) return boundedName(safeText(nested));
                if (e.tagName==='IMG') return boundedName(e.getAttribute('alt'));
                if (e.tagName==='INPUT' && ['button','submit','reset'].includes((e.type||'').toLowerCase())) return boundedName(e.getAttribute('value'));
                const title=e.getAttribute('title'); if(norm(title)) return boundedName(title);
                if (e.matches('input,textarea,select,[contenteditable],[role="textbox"],[role="searchbox"],[role="combobox"],[role="spinbutton"],[role="slider"]')) return '';
                return safeText(e);
              };
              const roleOf = e => {
                const explicit=(e.getAttribute('role')||'').trim().toLowerCase(); if(explicit) return explicit.split(/\s+/)[0];
                const tag=e.tagName.toLowerCase(), type=(e.getAttribute('type')||'').toLowerCase();
                if(tag==='a'&&e.hasAttribute('href')) return 'link';
                if(tag==='button'||tag==='summary') return 'button';
                if(tag==='textarea') return 'textbox';
                if(tag==='select') return e.multiple||e.size>1?'listbox':'combobox';
                if(tag==='option') return 'option';
                if(tag==='img') return e.getAttribute('alt')===''?'presentation':'img';
                if(/^h[1-6]$/.test(tag)) return 'heading';
                if(tag==='nav') return 'navigation';
                if(tag==='main') return 'main';
                if(tag==='ul'||tag==='ol') return 'list';
                if(tag==='li') return 'listitem';
                if(tag==='table') return 'table';
                if(tag==='th') return e.getAttribute('scope')==='row'?'rowheader':'columnheader';
                if(tag==='td') return 'cell';
                if(tag==='form'&&(e.getAttribute('aria-label')||e.querySelector('legend'))) return 'form';
                if(tag==='input') { if(['button','submit','reset','image'].includes(type)) return 'button'; if(type==='checkbox') return 'checkbox'; if(type==='radio') return 'radio'; if(type==='range') return 'slider'; if(type==='number') return 'spinbutton'; if(type==='search') return 'searchbox'; if(type==='hidden') return ''; return 'textbox'; }
                return '';
              };
              const referenceCurrent = ref => {
                try {
                  if(!ref||!ref.element||!ref.document||!ref.frameWindow||ref.element.ownerDocument!==ref.document||
                     !ref.element.isConnected||ref.frameWindow.document!==ref.document||
                     ref.element.getRootNode()!==(ref.shadowRoots.length?ref.shadowRoots[ref.shadowRoots.length-1]:ref.document)) return false;
                  for(const frame of ref.frameChain) {
                    if(!frame.element.isConnected||frame.element.ownerDocument!==frame.parentDocument||frame.element.contentDocument!==frame.childDocument||
                       frame.element.contentWindow!==frame.childWindow||frame.childWindow.document!==frame.childDocument||
                       frame.childWindow.location.origin==='null'||frame.childWindow.location.origin!==window.location.origin) return false;
                    for(let i=0;i<frame.parentShadowRoots.length;i++)
                      if(!frame.parentShadowHosts[i].isConnected||frame.parentShadowHosts[i].ownerDocument!==frame.parentDocument||
                        frame.parentShadowHosts[i].shadowRoot!==frame.parentShadowRoots[i]) return false;
                    const parentRoot=frame.parentShadowRoots.length?frame.parentShadowRoots[frame.parentShadowRoots.length-1]:frame.parentDocument;
                    if(frame.element.getRootNode()!==parentRoot) return false;
                  }
                  for(let i=0;i<ref.shadowRoots.length;i++)
                    if(!ref.shadowHosts[i].isConnected||ref.shadowHosts[i].ownerDocument!==ref.document||
                      ref.shadowHosts[i].shadowRoot!==ref.shadowRoots[i]) return false;
                  return true;
                } catch (_) { return false; }
              };
              const makeReference = (element, descriptor, frameOrdinal) => ({element,document:descriptor.document,
                frameWindow:descriptor.frameWindow,frameChain:descriptor.frameChain,
                shadowRoots:descriptor.shadowRoots,shadowHosts:descriptor.shadowHosts,frameOrdinal});
              const coverage={complete:true,scannedElements:0,frameCount:0,shadowRootCount:0,inaccessibleFrameCount:0,
                frameLimitReached:false,shadowLimitReached:false,elementLimitReached:false,textNodesVisited:0,textLimitReached:false};
              const scopes=[];
              let root=document, startDescriptor={document,frameWindow:window,frameOrdinal:0,frameChain:[],shadowRoots:[],shadowHosts:[]};
              if (p.scopeNonce!==null) {
                const nodes=window[p.scopeNonce], scope=Array.isArray(nodes)?nodes[p.scopeIndex]:null;
                if(!scope || !scope.element || !referenceCurrent(scope)) return JSON.stringify({code:'STALE_REFERENCE',count:0});
                root=scope.element; startDescriptor=scope;
                for(let i=0;i<scope.frameChain.length;i++) {
                  scopes.push({ordinal:i+1,parentOrdinal:i===0?0:i,depth:i+1,status:'accessible',reason:null});
                  coverage.frameCount++;
                  coverage.shadowRootCount+=scope.frameChain[i].parentShadowRoots.length;
                }
                coverage.shadowRootCount+=scope.shadowRoots.length;
                startDescriptor.frameOrdinal=scope.frameChain.length;
              }
              let candidates=[], seen=0;
              try {
                if(p.strategy==='css') {
                  (root.nodeType===Node.ELEMENT_NODE?root:startDescriptor.document.documentElement).matches(p.value);
                }
                walkAccessible(root,startDescriptor,(e,descriptor,frameOrdinal)=>{
                  seen=coverage.scannedElements;
                  if(!visible(e)) return;
                  let matched=false;
                  if(p.strategy==='css') matched=e.matches(p.value);
                  else if(p.strategy==='role') matched=roleOf(e)===norm(p.value).toLowerCase() && (p.name===null || textMatch(nameOf(e),p.name));
                  else if(p.strategy==='label') matched=Boolean(e.matches('input:not([type="hidden"]),textarea,select,button,[contenteditable],[role="textbox"],[role="searchbox"],[role="combobox"],[role="checkbox"],[role="radio"],[role="switch"],[role="button"]')) && textMatch(nameOf(e),p.value);
                  else if(p.strategy==='placeholder') matched=e.hasAttribute('placeholder') && attributeMatch(e.getAttribute('placeholder'),p.value);
                  else if(p.strategy==='text') matched=!e.matches(privateSelector+',script,style,noscript,template') && textMatch(actionable(e)?safeText(e):directText(e),p.value);
                  if(matched && visible(e)) candidates.push({element:e,descriptor,frameOrdinal});
                },coverage,scopes);
              } catch (_) { return JSON.stringify({code:'INVALID_SELECTOR',count:0}); }
              delete coverage.stopTraversal;
              coverage.inaccessibleFrameCount=scopes.filter(s=>s.status==='inaccessible').length;
              coverage.complete=coverage.inaccessibleFrameCount===0&&!coverage.frameLimitReached&&
                !coverage.shadowLimitReached&&!coverage.elementLimitReached;
              const partialResult=p.partial&&!coverage.complete;
              const withPartial=value=>{if(partialResult)value.partial=true;return JSON.stringify(value);};
              if(!coverage.complete&&!p.partial) return JSON.stringify({code:'INCOMPLETE_SCOPE',count:seen,scopeCoverage:coverage,frameScopes:scopes});
              if(textLimitExceeded) return withPartial({code:'LOCATE_LIMIT',count:seen,scopeCoverage:coverage,frameScopes:scopes});
              candidates=candidates.filter(c=>visible(c.element)&&c.descriptor.frameChain.every(f=>visible(f.element)));
              if(p.strategy==='text') {
                const candidateSet=new Set(candidates.map(c=>c.element)); let relationChecks=0;
                for(const item of candidates) {
                  const e=item.element;
                  if(!candidateSet.has(e)) continue;
                  for(let a=e.parentElement;a && a!==item.descriptor.document.documentElement;a=a.parentElement) {
                    if(++relationChecks>__RELATION_LIMIT__) return withPartial({code:'LOCATE_LIMIT',count:seen,scopeCoverage:coverage,frameScopes:scopes});
                    if(!candidateSet.has(a)) continue;
                    if(actionable(a)&&visible(a)&&textMatch(safeText(a),p.value)) { candidateSet.delete(e); break; }
                    candidateSet.delete(a);
                  }
                }
                candidates=candidates.filter(c=>candidateSet.has(c.element));
              }
              if(textLimitExceeded) return withPartial({code:'LOCATE_LIMIT',count:seen,scopeCoverage:coverage,frameScopes:scopes});
              if(candidates.length===0) {
                if(partialResult) return withPartial({code:'NOT_FOUND',count:0,scopeCoverage:coverage,frameScopes:scopes});
                return JSON.stringify({code:'NOT_FOUND',count:0,scopeCoverage:coverage,frameScopes:scopes});
              }
              if(candidates.length>1) {
                const matches=candidates.slice(0,5).map(c=>({tag:c.element.tagName.toLowerCase().slice(0,128),role:roleOf(c.element).slice(0,128),name:nameOf(c.element).slice(0,100),frameOrdinal:c.frameOrdinal,shadowDepth:c.descriptor.shadowRoots.length}));
                if(textLimitExceeded) return withPartial({code:'LOCATE_LIMIT',count:candidates.length,scopeCoverage:coverage,frameScopes:scopes});
                if(partialResult) return withPartial({code:'AMBIGUOUS',count:candidates.length,matches,scopeCoverage:coverage,frameScopes:scopes});
                return JSON.stringify({code:'AMBIGUOUS',count:candidates.length,matches,scopeCoverage:coverage,frameScopes:scopes});
              }
              if(partialResult&&(coverage.frameLimitReached||coverage.shadowLimitReached||coverage.elementLimitReached)) {
                return withPartial({code:'LOCATE_LIMIT',count:seen,scopeCoverage:coverage,frameScopes:scopes});
              }
              try { if(p.previousNonce!==null) delete window[p.previousNonce]; } catch (_) {}
              const chosen=candidates[0];
              Object.defineProperty(window,p.nonce,{value:[makeReference(chosen.element,chosen.descriptor,chosen.frameOrdinal)],
                configurable:true,enumerable:false});
              return withPartial({code:'OK',count:1,frameOrdinal:chosen.frameOrdinal,
                shadowDepth:chosen.descriptor.shadowRoots.length,scopeCoverage:coverage,frameScopes:scopes});
            })()
        """.trimIndent().replace("__LIMIT__", LOCATE_SCAN_LIMIT.toString())
            .replace("__TEXT_LIMIT__", LOCATE_TEXT_NODE_LIMIT.toString())
            .replace("__RELATION_LIMIT__", LOCATE_TEXT_NODE_LIMIT.toString())
            .replace("__ACCESSIBLE_WALKER__", accessibleWalker())
            .replace("__PARAMS__", params.toString())
    }

    fun observe(maxTextChars: Int, nonce: String, previousNonce: String?): String {
        val quotedNonce = JSONObject.quote(nonce)
        return """
            (() => {
              __ACCESSIBLE_WALKER__
              const key = __NONCE__;
              const previousKey = __PREVIOUS_NONCE__;
              if (typeof previousKey === 'string') { try { delete window[previousKey]; } catch (_) {} }
              const candidateSelector = 'a[href],button,input:not([type="hidden"]),textarea,select,summary,' +
                '[tabindex]:not([tabindex="-1"]),[onclick]';
              const actionRoles = new Set(['button','link','checkbox','radio','tab','menuitem','switch','option',
                'combobox','listbox','textbox','searchbox','slider','spinbutton']);
              const candidates = [];
              let candidatesTruncated=false;
              const scopeCoverage={complete:true,scannedElements:0,frameCount:0,shadowRootCount:0,inaccessibleFrameCount:0,
                frameLimitReached:false,shadowLimitReached:false,elementLimitReached:false,textNodesVisited:0,textLimitReached:false};
              const frameScopes=[];
              const textRoots=[];
              scopeCoverage._textRoots=textRoots;
              const topDescriptor={document,frameWindow:window,frameChain:[],shadowRoots:[],shadowHosts:[]};
              const isVisible=(element,descriptor)=>{
                if(!element||!element.isConnected||element.hidden) return false;
                for(let parent=element;parent;) {
                  const style=(parent.ownerDocument.defaultView||window).getComputedStyle(parent);
                  if(style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse'||style.contentVisibility==='hidden'||
                    Number(style.opacity)===0||parent.hasAttribute('hidden')||parent.getAttribute('aria-hidden')==='true') return false;
                  if(parent.parentElement) parent=parent.parentElement;
                  else { const root=parent.getRootNode(); parent=root&&root.host?root.host:null; }
                }
                const rect=element.getBoundingClientRect(), win=element.ownerDocument.defaultView;
                if(!win||rect.width<=0||rect.height<=0||element.getClientRects().length===0) return false;
                return descriptor.frameChain.every(frame=>frame.element.isConnected&&frame.element.contentDocument===frame.childDocument&&
                  isVisible(frame.element,{frameChain:[]}));
              };
              walkAccessible(document,topDescriptor,(element,descriptor,frameOrdinal)=>{
                if(candidates.length>=80) { candidatesTruncated=true; return; }
                if (!element.matches(candidateSelector) && !element.isContentEditable &&
                    !actionRoles.has((element.getAttribute('role') || '').trim().toLowerCase())) return;
                if(isVisible(element,descriptor)) candidates.push({element,descriptor,frameOrdinal});
              },scopeCoverage,frameScopes);
              delete scopeCoverage.stopTraversal;
              scopeCoverage.inaccessibleFrameCount=frameScopes.filter(s=>s.status==='inaccessible').length;
              scopeCoverage.complete=scopeCoverage.inaccessibleFrameCount===0&&!scopeCoverage.frameLimitReached&&
                !scopeCoverage.shadowLimitReached&&!scopeCoverage.elementLimitReached;
              const clean = (v, n) => String(v || '').replace(/\s+/g, ' ').trim().slice(0, n);
              const privateValueElement = e => e.matches(
                'input,textarea,select,[contenteditable],[role="textbox"],[role="searchbox"],' +
                '[role="combobox"],[role="spinbutton"],[role="slider"]'
              );
              const excludedTextElement = e => e.matches(
                'script,style,noscript,template,[hidden],[aria-hidden="true"]'
              ) || privateValueElement(e);
              let textTruncated = false;
              const visibleText = (root, limit) => {
                if (!root || (root.nodeType === Node.ELEMENT_NODE && excludedTextElement(root))) return '';
                const doc = root.ownerDocument || root;
                const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                const parts = [];
                let used = 0, visited = 0;
                let node;
                while ((node = walker.nextNode())) {
                  if (used >= limit) { textTruncated = true; break; }
                  if (scopeCoverage.textNodesVisited >= 20000) { scopeCoverage.textLimitReached=true; textTruncated=true; break; }
                  scopeCoverage.textNodesVisited++;
                  visited++;
                  let element = node.parentElement, allowed = true;
                  while (element) {
                    if (excludedTextElement(element)) { allowed = false; break; }
                    const style = (element.ownerDocument.defaultView || window).getComputedStyle(element);
                    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' ||
                        style.contentVisibility === 'hidden' || Number(style.opacity) === 0 || element.getAttribute('aria-hidden') === 'true') {
                      allowed = false; break;
                    }
                    if(element.parentElement) element=element.parentElement;
                    else { const root=element.getRootNode(); element=root&&root.host?root.host:null; }
                  }
                  if (allowed) {
                    const value = node.nodeValue || '';
                    const bounded = value.slice(0, limit - used);
                    parts.push(bounded);
                    used += bounded.length;
                    if (bounded.length < value.length) textTruncated = true;
                  }
                }
                return clean(parts.join(' '), limit);
              };
              const implicitRole = e => {
                const tag = e.tagName.toLowerCase(), type = (e.getAttribute('type') || '').toLowerCase();
                if (tag === 'a' && e.hasAttribute('href')) return 'link';
                if (tag === 'button' || tag === 'summary') return 'button';
                if (tag === 'textarea') return 'textbox';
                if (tag === 'select') return e.multiple || e.size > 1 ? 'listbox' : 'combobox';
                if (tag === 'option') return 'option';
                if (tag === 'img') return e.getAttribute('alt') === '' ? 'presentation' : 'img';
                if (/^h[1-6]$/.test(tag)) return 'heading';
                if (tag === 'nav') return 'navigation';
                if (tag === 'main') return 'main';
                if (tag === 'ul' || tag === 'ol') return 'list';
                if (tag === 'li') return 'listitem';
                if (tag === 'table') return 'table';
                if (tag === 'th') return e.getAttribute('scope') === 'row' ? 'rowheader' : 'columnheader';
                if (tag === 'td') return 'cell';
                if (tag === 'form' && (e.getAttribute('aria-label') || e.querySelector('legend'))) return 'form';
                if (tag === 'input') {
                  if (['button','submit','reset','image'].includes(type)) return 'button';
                  if (type === 'checkbox') return 'checkbox';
                  if (type === 'radio') return 'radio';
                  if (type === 'range') return 'slider';
                  if (type === 'number') return 'spinbutton';
                  if (type === 'search') return 'searchbox';
                  if (type === 'hidden') return '';
                  return 'textbox';
                }
                return '';
              };
              const accessibleName = e => {
                const doc=e.ownerDocument, ids = (e.getAttribute('aria-labelledby') || '').trim().split(/\s+/).filter(Boolean);
                if (ids.length) {
                  const value = ids.map(id => doc.getElementById(id)).filter(Boolean)
                    .map(node => visibleText(node, 512)).join(' ');
                  if (clean(value, 512)) return clean(value, 512);
                }
                if (clean(e.getAttribute('aria-label'), 512)) return clean(e.getAttribute('aria-label'), 512);
                if (e.labels && e.labels.length) {
                  const value = Array.from(e.labels).map(label => visibleText(label, 512)).join(' ');
                  if (clean(value, 512)) return clean(value, 512);
                }
                const nested = e.closest('label');
                if (nested && clean(visibleText(nested, 512), 512)) return clean(visibleText(nested, 512), 512);
                if (e.tagName==='IMG') return clean(e.getAttribute('alt'),512);
                if (e.tagName==='INPUT' && ['button','submit','reset'].includes((e.type||'').toLowerCase())) return clean(e.getAttribute('value'),512);
                if (clean(e.getAttribute('title'), 512)) return clean(e.getAttribute('title'), 512);
                return privateValueElement(e) ? '' : visibleText(e, 512);
              };
              const elementResults=candidates.map((candidate,index)=>{
                const e=candidate.element;
                return {index,tag:e.tagName.toLowerCase(),type:clean(e.getAttribute('type'),32),
                  role:clean(e.getAttribute('role')||implicitRole(e),48),accessibleName:clean(accessibleName(e),160),
                  placeholder:clean(e.getAttribute('placeholder'),160),label:clean(accessibleName(e)||e.getAttribute('placeholder'),160),
                  href:e.tagName==='A'?clean(e.href,512):'',frameOrdinal:candidate.frameOrdinal,
                  shadowDepth:candidate.descriptor.shadowRoots.length};
              });
              let combinedText='', remainingText=$maxTextChars;
              for(const textRoot of textRoots) {
                if(!textRoot.descriptor.frameChain.every(frame=>isVisible(frame.element,{frameChain:[]}))) continue;
                if(remainingText<=0) { textTruncated=true; break; }
                const piece=visibleText(textRoot.root,remainingText);
                if(piece) { combinedText+=(combinedText?' ':'')+piece; remainingText=$maxTextChars-combinedText.length; }
              }
              delete scopeCoverage._textRoots;
              try { delete window[key]; } catch (_) {}
              Object.defineProperty(window,key,{value:candidates.map(candidate=>({element:candidate.element,document:candidate.descriptor.document,
                frameWindow:candidate.descriptor.frameWindow,frameChain:candidate.descriptor.frameChain,
                shadowRoots:candidate.descriptor.shadowRoots,shadowHosts:candidate.descriptor.shadowHosts,
                frameOrdinal:candidate.frameOrdinal})),configurable:true,enumerable:false});
              const topCandidates=candidates.map((candidate,index)=>({candidate,index}))
                .filter(item=>item.candidate.frameOrdinal===0);
              let overlayObservation=null;
              try { overlayObservation=(__OVERLAY_OBSERVATION__)({
                registeredActionable:topCandidates.map(item=>item.candidate.element),
                referenceMap:topCandidates.map(item=>[item.candidate.element,item.index])
              }); } catch (_) {}
              return JSON.stringify({
                title: clean(document.title, 256),
                url: location.href.slice(0, 2048),
                readyState: document.readyState,
                text: clean(combinedText,$maxTextChars),
                textTruncated:textTruncated||scopeCoverage.textLimitReached,
                elements: elementResults,
                truncated: textTruncated || scopeCoverage.elementLimitReached || candidatesTruncated,
                elementsTruncated: scopeCoverage.elementLimitReached || candidatesTruncated,
                scopeCoverage,frameScopes,topFrameOnly:false,openShadowRootsSupported:true,
                closedShadowRootsSupported:false,crossOriginFramesSupported:false,overlayObservation
              });
            })()
        """.trimIndent().replace("__NONCE__", quotedNonce)
            .replace("__PREVIOUS_NONCE__", previousNonce?.let(JSONObject::quote) ?: "null")
            .replace("__OVERLAY_OBSERVATION__", BrowserOverlayObservation.script())
            .replace("__ACCESSIBLE_WALKER__", accessibleWalker())
    }

    /** Read-only snapshot of one rendered window in a caller-configured virtual list. */
    fun collectWindow(config: JSONObject, nonce: String, previousNonce: String?): String {
        val params = JSONObject(config.toString())
        val quotedNonce = JSONObject.quote(nonce)
        return """
            (() => {
              const p = __PARAMS__;
              const key=__NONCE__, previousKey=__PREVIOUS_NONCE__;
              const started=Date.now(), maxElements=5000, maxTextNodes=20000, maxMs=100;
              const clean = (value, limit) => String(value == null ? '' : value).slice(0,limit*4+32).replace(/\s+/g,' ').trim().slice(0,limit);
              const fail = code => JSON.stringify({code});
              if (typeof previousKey === 'string') { try { delete window[previousKey]; } catch (_) {} }
              try { delete window[key]; } catch (_) {}
              const exhausted = () => Date.now()-started >= maxMs;
              const sensitive = element => {
                if (!element || !element.tagName) return false;
                const tag = String(element.tagName).toLowerCase();
                const role = String(element.getAttribute('role') || '').trim().toLowerCase();
                return ['script','style','noscript','template','input','textarea','select'].includes(tag) ||
                  element.isContentEditable || element.hasAttribute('contenteditable') ||
                  ['textbox','searchbox','combobox','spinbutton','slider'].includes(role) ||
                  (tag === 'input' && String(element.getAttribute('type') || '').toLowerCase() === 'password');
              };
              const visibleAndSafe = element => {
                for (let parent=element; parent;) {
                  if (!parent.isConnected || parent.hidden || parent.hasAttribute('hidden') ||
                      parent.getAttribute('aria-hidden') === 'true' || sensitive(parent)) return false;
                  const view=parent.ownerDocument && parent.ownerDocument.defaultView;
                  const style=(view || window).getComputedStyle(parent);
                  if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' ||
                      style.contentVisibility === 'hidden' || Number(style.opacity) === 0) return false;
                  parent=parent.parentElement;
                }
                const rect=element.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0 && element.getClientRects().length > 0;
              };
              const visibleText = (root, limit) => {
                if (!root || !visibleAndSafe(root)) return {text:'',truncated:false};
                const doc=root.ownerDocument, walker=doc.createTreeWalker(root,NodeFilter.SHOW_TEXT);
                let text='', truncated=false, node;
                while ((node=walker.nextNode())) {
                  if (++visitedTextNodes > maxTextNodes || exhausted()) return {text:clean(text,limit),truncated:true};
                  let safe=true;
                  for (let parent=node.parentElement; parent; parent=parent.parentElement) {
                    if (sensitive(parent) || parent.hidden || parent.hasAttribute('hidden') ||
                        parent.getAttribute('aria-hidden') === 'true') { safe=false; break; }
                    const view=parent.ownerDocument && parent.ownerDocument.defaultView;
                    const style=(view || window).getComputedStyle(parent);
                    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' ||
                        style.contentVisibility === 'hidden' || Number(style.opacity) === 0) { safe=false; break; }
                    if (parent === root) break;
                  }
                  if (!safe) continue;
                  const value=String(node.nodeValue || '');
                  const remaining=limit-text.length;
                  if (remaining <= 0) { truncated=true; break; }
                  const part=value.slice(0,remaining);
                  text += (text && part ? ' ' : '') + part;
                  if (part.length < value.length) { truncated=true; break; }
                }
                return {text:clean(text,limit),truncated};
              };
              let visitedTextNodes=0;
              let container;
              try {
                const containerWalker=document.createTreeWalker(document,NodeFilter.SHOW_ELEMENT);
                let element, scanned=0, containerCount=0;
                while ((element=containerWalker.nextNode())) {
                  if (++scanned > maxElements || exhausted()) return fail('SCAN_LIMIT');
                  if (element.matches(p.containerSelector) && visibleAndSafe(element)) {
                    container=element;
                    if (++containerCount > 1) return fail('AMBIGUOUS_CONTAINER');
                  }
                }
                if (containerCount !== 1) return fail('CONTAINER_NOT_FOUND');
                const style = getComputedStyle(container);
                if (!visibleAndSafe(container) || !['auto','scroll','overlay'].includes(style.overflowY) ||
                    container.clientHeight <= 0 || container.scrollHeight < container.clientHeight) return fail('NOT_SCROLLABLE');
                const messageWalker=container.ownerDocument.createTreeWalker(container,NodeFilter.SHOW_ELEMENT);
                const records = [];
                let truncated = false, excludedCount = 0, messageScanned=0, item;
                while ((item=messageWalker.nextNode())) {
                  if (++messageScanned > maxElements || exhausted()) { truncated=true; break; }
                  if (!item.matches(p.messageSelector) || !visibleAndSafe(item)) continue;
                  if (p.excludeSelector && (item.matches(p.excludeSelector) || item.querySelector(p.excludeSelector))) {
                    excludedCount++;
                    continue;
                  }
                  if (records.length >= p.maxRecords) { truncated=true; break; }
                  const keyRaw = p.keyAttribute ? item.getAttribute(p.keyAttribute) : null;
                  const key = keyRaw !== null && String(keyRaw).length <= 256 ? clean(keyRaw,256) : null;
                  const contentNode = p.contentSelector ? item.querySelector(p.contentSelector) : item;
                  const authorNode = p.authorSelector ? item.querySelector(p.authorSelector) : null;
                  const orderRaw = p.orderAttribute ? item.getAttribute(p.orderAttribute) : null;
                  const contentResult = visibleText(contentNode, p.maxContentChars);
                  const authorResult = authorNode && visibleAndSafe(authorNode) ?
                    (p.authorAttribute ? {text:clean(authorNode.getAttribute(p.authorAttribute),128),truncated:false} : visibleText(authorNode,128)) : null;
                  if (!contentNode || !visibleAndSafe(contentNode)) { truncated=true; continue; }
                  if (keyRaw !== null && String(keyRaw).length > 256 || contentResult.truncated || (authorResult && authorResult.truncated)) truncated = true;
                  records.push({key: key || null,
                    author: authorResult ? authorResult.text || null : null,
                    content:contentResult.text, order: orderRaw !== null && /^-?\d+$/.test(orderRaw.trim()) ? Number(orderRaw) : null});
                }
                if (exhausted() || visitedTextNodes > maxTextNodes) truncated = true;
                const totalNode = p.totalCountSelector ? document.querySelector(p.totalCountSelector) : null;
                const totalRaw = totalNode && visibleAndSafe(totalNode) ?
                  (p.totalCountAttribute ? totalNode.getAttribute(p.totalCountAttribute) : visibleText(totalNode,32).text) : null;
                const totalText = totalRaw == null ? '' : String(totalRaw).trim();
                const totalCount = /^\d+$/.test(totalText) ? Number(totalText) : null;
                const top = Math.max(0, Number(container.scrollTop) || 0);
                const maxTop = Math.max(0, container.scrollHeight - container.clientHeight);
                Object.defineProperty(window,key,{value:[{element:container,document:container.ownerDocument,
                  frameWindow:window,frameChain:[],shadowRoots:[],shadowHosts:[],frameOrdinal:0}],
                  configurable:true,enumerable:false});
                return JSON.stringify({code:'OK',container:{scrollTop:top,scrollHeight:container.scrollHeight,
                  clientHeight:container.clientHeight,startReached:top<=1,endReached:top>=maxTop-1,
                  totalCount},records,truncated,excludedCount,containerRefIndex:0});
              } catch (_) { return fail('COLLECT_FAILED'); }
            })()
        """.trimIndent().replace("__PARAMS__", params.toString())
            .replace("__NONCE__", quotedNonce)
            .replace("__PREVIOUS_NONCE__", previousNonce?.let(JSONObject::quote) ?: "null")
    }

    fun click(nonce: String, index: Int, authorizationNonce: String): String = actionPrefix(nonce, index) + """
              if (!e || !e.isConnected) return JSON.stringify({code:'STALE_REFERENCE'});
              const stale=referenceStatus(); if(stale) return JSON.stringify({code:stale});
              if (!isVisible(e)) return JSON.stringify({code:'NOT_VISIBLE'});
              if (e.disabled || composedClosest(e,'[aria-disabled="true"]')) return JSON.stringify({code:'DISABLED'});
              if (!window[__GATE__] || !window[__GATE__].authorize(__AUTHORIZATION_NONCE__)) {
                return JSON.stringify({code:'AUTHORIZATION_REVOKED'});
              }
              e.scrollIntoView({block:'center',inline:'center'});
              if (isObscured(e)) return JSON.stringify({code:'OBSCURED'});
              e.click();
              return JSON.stringify({code:'OK'});
            })()
        """.trimIndent().replace("__GATE__", JSONObject.quote(ACTION_GATE_NAME))
            .replace("__AUTHORIZATION_NONCE__", JSONObject.quote(authorizationNonce))

    fun type(nonce: String, index: Int, text: String, replace: Boolean, authorizationNonce: String): String {
        val quoted = JSONObject.quote(text)
        val valueExpression = if (replace) "value" else "e.value + value"
        val contentExpression = if (replace) "value" else "e.textContent + value"
        return actionPrefix(nonce, index) + """
              if (!e || !e.isConnected) return JSON.stringify({code:'STALE_REFERENCE'});
              const stale=referenceStatus(); if(stale) return JSON.stringify({code:stale});
              if (!isVisible(e)) return JSON.stringify({code:'NOT_VISIBLE'});
              if (e.disabled || composedClosest(e,'[aria-disabled="true"]')) return JSON.stringify({code:'DISABLED'});
              if (e.readOnly || composedClosest(e,'[aria-readonly="true"]') ||
                  !(e instanceof ref.frameWindow.HTMLTextAreaElement || e.isContentEditable ||
                    (e instanceof ref.frameWindow.HTMLInputElement && ['text','search','email','url','tel'].includes(e.type)))) {
                return JSON.stringify({code:'ACTION_REJECTED'});
              }
              if (!window[__GATE__] || !window[__GATE__].authorize(__AUTHORIZATION_NONCE__)) {
                return JSON.stringify({code:'AUTHORIZATION_REVOKED'});
              }
              e.scrollIntoView({block:'center',inline:'center'});
              const obscurer=obscuredBy(e);
              if (obscurer) return JSON.stringify({code:'OBSCURED',obscuredBy:obscuredMetadata(obscurer)});
              e.focus();
              const value = $quoted;
              if (e.isContentEditable) {
                e.textContent = $contentExpression;
              } else {
                const proto = e.tagName === 'TEXTAREA' ? ref.frameWindow.HTMLTextAreaElement.prototype : ref.frameWindow.HTMLInputElement.prototype;
                const descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
                if (!descriptor || !descriptor.set) return JSON.stringify({code:'ACTION_REJECTED'});
                descriptor.set.call(e, $valueExpression);
              }
              e.dispatchEvent(new ref.frameWindow.InputEvent('input', {bubbles:true, inputType:'insertText', data:value}));
              e.dispatchEvent(new ref.frameWindow.Event('change', {bubbles:true}));
              return JSON.stringify({code:'OK'});
            })()
        """.trimIndent().replace("__GATE__", JSONObject.quote(ACTION_GATE_NAME))
            .replace("__AUTHORIZATION_NONCE__", JSONObject.quote(authorizationNonce))
    }

    fun uploadActivationTarget(nonce: String, index: Int, authorizationNonce: String): String = actionPrefix(nonce, index) + """
              const fail=code=>JSON.stringify({code});
              if (!e || !e.isConnected) return fail('STALE_REFERENCE');
              const stale=referenceStatus(); if(stale) return fail(stale);
              if (ref.document!==document || ref.frameChain.length!==0 || ref.shadowRoots.length!==0) return fail('UNSUPPORTED');
              if (!window[__GATE__] || !window[__GATE__].authorize(__AUTHORIZATION_NONCE__)) return fail('AUTHORIZATION_REVOKED');
              let target=null, input=null, activation='input';
              if (e instanceof HTMLInputElement && e.type==='file') {
                input=e;
                const labels=Array.from(e.labels||[]).filter(label=>label.ownerDocument===e.ownerDocument&&isVisible(label));
                if(labels.length!==1) return fail('UPLOAD_TARGET_REJECTED');
                target=labels[0]; activation='label';
              }
              else if (e instanceof HTMLLabelElement && e.control instanceof HTMLInputElement && e.control.type==='file' &&
                       e.control.ownerDocument===e.ownerDocument && e.control.labels && Array.from(e.control.labels).includes(e)) {
                target=e; input=e.control; activation='label';
              } else return fail('UPLOAD_TARGET_REJECTED');
              if (!isVisible(target) || input.disabled || input.matches(':disabled') || target.getAttribute('aria-disabled')==='true') return fail('UPLOAD_TARGET_REJECTED');
              if (activation==='input' && isObscured(target)) return fail('UPLOAD_TARGET_OBSCURED');
              if (activation==='label' && isObscured(target)) return fail('UPLOAD_TARGET_OBSCURED');
              const r=target.getBoundingClientRect(), win=window;
              const x=r.left+r.width/2, y=r.top+r.height/2;
              if (![x,y,r.width,r.height,win.innerWidth,win.innerHeight].every(Number.isFinite) || r.width<=0 || r.height<=0 ||
                  x<0 || y<0 || x>=win.innerWidth || y>=win.innerHeight) return fail('UPLOAD_TARGET_REJECTED');
              const hit=document.elementFromPoint(x,y);
              if (!(hit===target || target.contains(hit))) return fail('UPLOAD_TARGET_OBSCURED');
              return JSON.stringify({code:'READY',x,y,width:r.width,height:r.height,viewportWidth:win.innerWidth,
                viewportHeight:win.innerHeight,accept:input.accept,multiple:input.multiple,activation});
            })()
        """.trimIndent().replace("__GATE__", JSONObject.quote(ACTION_GATE_NAME))
            .replace("__AUTHORIZATION_NONCE__", JSONObject.quote(authorizationNonce))

    fun scroll(nonce: String, index: Int, direction: String, steps: Int, authorizationNonce: String): String {
        val amount = if (direction == "up") -1 else 1
        return actionPrefix(nonce, index) + """
              if (!e || !e.isConnected) return JSON.stringify({code:'STALE_REFERENCE'});
              const stale=referenceStatus(); if(stale) return JSON.stringify({code:stale});
              if (!window[__GATE__] || !window[__GATE__].authorize(__AUTHORIZATION_NONCE__)) {
                return JSON.stringify({code:'AUTHORIZATION_REVOKED'});
              }
              const style = ref.frameWindow.getComputedStyle(e);
              if (e.scrollHeight <= e.clientHeight || !['auto','scroll','overlay'].includes(style.overflowY)) {
                return JSON.stringify({code:'ACTION_REJECTED',scrollTop:e.scrollTop});
              }
              const before = e.scrollTop;
              const delta = Math.max(120, Math.floor(e.clientHeight * 0.8)) * $amount * $steps;
              e.scrollBy({top:delta,behavior:'instant'});
              return JSON.stringify({code:e.scrollTop === before ? 'NO_EFFECT' : 'OK',scrollTop:e.scrollTop});
            })()
        """.trimIndent().replace("__GATE__", JSONObject.quote(ACTION_GATE_NAME))
            .replace("__AUTHORIZATION_NONCE__", JSONObject.quote(authorizationNonce))
    }

    private fun actionPrefix(nonce: String, index: Int): String {
        val quotedNonce = JSONObject.quote(nonce)
        return """
            (() => {
              const nodes = window[__NONCE__];
              const ref = Array.isArray(nodes) ? nodes[$index] : null;
              const e = ref && ref.element;
              const referenceStatus = () => {
                try {
                  if(!ref||!e||!ref.document||!ref.frameWindow||e.ownerDocument!==ref.document||!e.isConnected||
                    ref.frameWindow.document!==ref.document||e.getRootNode()!==(ref.shadowRoots.length?ref.shadowRoots[ref.shadowRoots.length-1]:ref.document)) return 'STALE_REFERENCE';
                  for(const frame of ref.frameChain) {
                    if(!frame.element.isConnected||frame.element.ownerDocument!==frame.parentDocument||frame.element.contentDocument!==frame.childDocument||frame.element.contentWindow!==frame.childWindow||
                      frame.childWindow.document!==frame.childDocument||frame.childWindow.location.origin==='null'||
                      frame.childWindow.location.origin!==window.location.origin) return 'FRAME_UNAVAILABLE';
                    for(let i=0;i<frame.parentShadowRoots.length;i++)
                      if(!frame.parentShadowHosts[i].isConnected||frame.parentShadowHosts[i].ownerDocument!==frame.parentDocument||
                        frame.parentShadowHosts[i].shadowRoot!==frame.parentShadowRoots[i]) return 'STALE_REFERENCE';
                    const parentRoot=frame.parentShadowRoots.length?frame.parentShadowRoots[frame.parentShadowRoots.length-1]:frame.parentDocument;
                    if(frame.element.getRootNode()!==parentRoot) return 'STALE_REFERENCE';
                  }
                  for(let i=0;i<ref.shadowRoots.length;i++)
                    if(!ref.shadowHosts[i].isConnected||ref.shadowHosts[i].ownerDocument!==ref.document||
                      ref.shadowHosts[i].shadowRoot!==ref.shadowRoots[i]) return 'STALE_REFERENCE';
                } catch (_) { return 'FRAME_UNAVAILABLE'; }
                return null;
              };
              const isVisible = target => {
                if (!target || !target.isConnected || target.hidden) return false;
                for (let n=target; n;) {
                  const style=(n.ownerDocument.defaultView||window).getComputedStyle(n);
                  if(style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse'||style.contentVisibility==='hidden'||Number(style.opacity)===0||n.hidden||n.getAttribute('aria-hidden')==='true') return false;
                  if(n.parentElement) n=n.parentElement;
                  else { const root=n.getRootNode(); n=root&&root.host?root.host:null; }
                }
                const r=target.getBoundingClientRect(); return r.width>0&&r.height>0&&target.getClientRects().length>0;
              };
              const composedClosest = (target, selector) => {
                for(let n=target;n;) { if(n.nodeType===Node.ELEMENT_NODE&&n.matches(selector)) return n;
                  if(n.parentElement)n=n.parentElement;else{const root=n.getRootNode();n=root&&root.host?root.host:null;} }
                return null;
              };
              const associatedLabel = (target, hit) => {
                if(!target||!hit||hit.tagName!=='LABEL'||hit.ownerDocument!==target.ownerDocument) return false;
                try {
                  if(hit.control===target) return true;
                  return target.id!==''&&hit.htmlFor===target.id&&target.labels&&Array.from(target.labels).includes(hit);
                } catch (_) { return false; }
              };
              const obscuringElementInDocument = (target, doc) => {
                const r=target.getBoundingClientRect();
                const win=doc.defaultView,x=r.left+r.width/2,y=r.top+r.height/2;
                if(!win||x<0||y<0||x>=win.innerWidth||y>=win.innerHeight) return target;
                const root=target.getRootNode();
                if(root&&root.host&&typeof root.elementFromPoint!=='function') return target;
                const hit=root&&root.host?root.elementFromPoint(x,y):doc.elementFromPoint(x,y);
                if(!hit)return target;
                if(hit===target||target.contains(hit)||(root&&root.host&&hit===root.host)||associatedLabel(target,hit)) return null;
                return hit;
              };
              const obscuredBy = target => {
                const own=obscuringElementInDocument(target,ref.document); if(own) return own;
                for(const frame of ref.frameChain) {
                  if(!isVisible(frame.element)) return frame.element;
                  const outer=obscuringElementInDocument(frame.element,frame.parentDocument); if(outer) return outer;
                }
                return null;
              };
              const isObscured = target => Boolean(obscuredBy(target));
              const obscuredMetadata = element => ({tag:String(element.tagName||'').toLowerCase().slice(0,64),
                id:String(element.id||'').slice(0,128),className:String(typeof element.className==='string'?element.className:'').slice(0,256)});
        """.trimIndent().replace("__NONCE__", quotedNonce)
    }
}
