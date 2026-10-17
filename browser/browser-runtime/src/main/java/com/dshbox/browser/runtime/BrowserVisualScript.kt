package com.dshbox.browser.runtime

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.WeakHashMap

/** Installs a document-start closure for conservative screenshot-bound DOM actions. */
internal object BrowserVisualScript {
    private val key = "__dshVisual_" + java.util.UUID.randomUUID().toString().replace("-", "")
    private val handlers = WeakHashMap<WebView, ScriptHandler>()

    @Synchronized
    fun install(view: WebView): Boolean {
        if (!runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)) return false
        if (handlers.containsKey(view)) return true
        val source = """
            (()=>{
              const w=window,d=document,k=${JSONObject.quote(key)},apply=Reflect.apply,freeze=Object.freeze;
              const desc=Object.getOwnPropertyDescriptor,getProto=Object.getPrototypeOf,abs=Math.abs,indexOf=String.prototype.indexOf;
              const find=(o,n)=>{for(let p=o,i=0;p&&i<12;p=apply(getProto,Object,[p]),i++){const x=desc(p,n);if(x)return x}return null};
              const getter=(o,n)=>find(o,n)?.get,method=(o,n)=>find(o,n)?.value;
              const vpGet=getter(w,'visualViewport'),dprGet=getter(w,'devicePixelRatio');
              const vv=vpGet?apply(vpGet,w,[]):null;
              const vvWidth=vv&&getter(vv,'width'),vvHeight=vv&&getter(vv,'height');
              const vvLeft=vv&&getter(vv,'offsetLeft'),vvTop=vv&&getter(vv,'offsetTop'),vvScale=vv&&getter(vv,'scale');
              const docRoot=getter(d,'documentElement'),elementAt=method(d,'elementFromPoint'),elementsAt=method(d,'elementsFromPoint');
              const getAnims=method(d,'getAnimations'),animationProto=w.Animation&&Animation.prototype;
              const playState=animationProto&&getter(animationProto,'playState'),computed=method(w,'getComputedStyle'),stringifier=JSON.stringify;
              const createElement=method(d,'createElement'),probe=createElement?apply(createElement,d,['button']):null;
              const ownerDoc=probe&&getter(probe,'ownerDocument'),rootNode=probe&&method(probe,'getRootNode'),parent=probe&&getter(probe,'parentElement');
              const localName=probe&&getter(probe,'localName'),namespace=probe&&getter(probe,'namespaceURI');
              const shadow=probe&&getter(probe,'shadowRoot'),connected=probe&&getter(probe,'isConnected');
              const rect=probe&&method(probe,'getBoundingClientRect'),matches=probe&&method(probe,'matches');
              const query=probe&&method(probe,'querySelector'),attr=probe&&method(probe,'getAttribute');
              const createWalker=method(d,'createTreeWalker'),walkerProbe=createWalker?apply(createWalker,d,[d,1,null]):null;
              const nextNode=walkerProbe&&method(walkerProbe,'nextNode');
              const style=probe&&computed?apply(computed,w,[probe]):null,styleValue=style&&method(style,'getPropertyValue');
              const nativeClick=probe&&method(probe,'click'),disabled=probe&&getter(probe,'disabled');
              const mediaProbe=createElement?apply(createElement,d,['video']):null;
              const mediaPaused=mediaProbe&&getter(mediaProbe,'paused'),mediaSeeking=mediaProbe&&getter(mediaProbe,'seeking');
              const MO=w.MutationObserver,RO=w.ResizeObserver,moObserve=MO&&method(MO.prototype,'observe'),roObserve=RO&&method(RO.prototype,'observe');
              const add=method(w,'addEventListener'),getRandom=w.crypto&&method(w.crypto,'getRandomValues'),objectCreate=Object.create;
              const docTokenBytes=new Uint8Array(16);try{apply(getRandom,w.crypto,[docTokenBytes])}catch(_){return}
              let docToken='';for(let i=0;i<docTokenBytes.length;i++)docToken+=docTokenBytes[i].toString(16).padStart(2,'0');
              const gate=w.__dshBrowserActionGate,authorizeVisual=gate&&gate.authorizeVisual;
              if(!vpGet||!dprGet||!vvWidth||!vvHeight||!vvLeft||!vvTop||!vvScale||!docRoot||!elementAt||!elementsAt||!getAnims||!playState||
                 !computed||!ownerDoc||!rootNode||!parent||!localName||!namespace||!shadow||!connected||!rect||!matches||!query||!attr||!styleValue||
                 !createWalker||!nextNode||!nativeClick||!MO||!RO||!moObserve||!roObserve||!add||!stringifier||!objectCreate||typeof authorizeVisual!=='function')return;
              let generation=0;const changed=()=>{generation++};
              const resize=new RO(changed),observeRoot=()=>{try{const root=apply(docRoot,d,[]);if(root)apply(roObserve,resize,[root])}catch(_){}};
              const mutation=new MO(()=>{changed();observeRoot()});
              try{apply(moObserve,mutation,[d,{subtree:true,childList:true,attributes:true,characterData:true}]);observeRoot()}catch(_){return}
              apply(add,w,['resize',changed,true]);apply(add,w,['scroll',changed,true]);
              // getAnimations() materializes the browser-provided list before its length is checked.
              // Cap subsequent inspection, but do not claim this bounds the browser API's allocation.
              const animationActive=()=>{try{const list=apply(getAnims,d,[]);if(!list||list.length>256)return true;
                for(let i=0;i<list.length;i++)if(apply(playState,list[i],[])==='running')return true;return false}catch(_){return true}};
              const read=()=>{try{const v=apply(vpGet,w,[]),root=apply(docRoot,d,[]);if(!v||!root)return null;
                const result=apply(objectCreate,Object,[null]);
                result.documentToken=docToken;result.mutationGeneration=generation;result.animationsActive=animationActive();
                result.visualViewportWidth=apply(vvWidth,v,[]);result.visualViewportHeight=apply(vvHeight,v,[]);
                result.visualViewportOffsetX=apply(vvLeft,v,[]);result.visualViewportOffsetY=apply(vvTop,v,[]);
                result.visualViewportScale=apply(vvScale,v,[]);result.devicePixelRatio=apply(dprGet,w,[]);return freeze(result);}catch(_){return null}};
              const click=(p)=>{let dispatched=false;const out=code=>apply(stringifier,JSON,[{code}]);try{
                const s=read();if(!s||s.documentToken!==p.documentToken||s.mutationGeneration!==p.mutationGeneration||s.animationsActive||
                  apply(abs,Math,[s.visualViewportWidth-p.visualViewportWidth])>.01||apply(abs,Math,[s.visualViewportHeight-p.visualViewportHeight])>.01||
                  apply(abs,Math,[s.visualViewportOffsetX-p.visualViewportOffsetX])>.01||apply(abs,Math,[s.visualViewportOffsetY-p.visualViewportOffsetY])>.01)return out('STALE_SCREENSHOT');
                const x=p.x,y=p.y,e=apply(elementAt,d,[x,y]);if(!e||apply(ownerDoc,e,[])!==d||apply(rootNode,e,[])!==d)return out('UNSUPPORTED');
                const tag=apply(localName,e,[]),surface=p.nativeSurface===true;
                if(surface?(tag!=='canvas'&&tag!=='video'):(tag!=='a'&&tag!=='button'))return out('UNSUPPORTED');
                if(surface&&tag==='video'&&(!mediaPaused||!mediaSeeking||!apply(mediaPaused,e,[])||apply(mediaSeeking,e,[])))return out('UNSUPPORTED');
                const forbidden=surface?'iframe,frame,svg,audio,input,textarea,select,[contenteditable],[type="password"],[type="file"]':
                  'iframe,frame,svg,canvas,video,audio,input,textarea,select,[contenteditable],[type="password"],[type="file"]';
                const root=apply(docRoot,d,[]);let opacity=1;
                for(let a=e;a;a=apply(parent,a,[])){
                  if(apply(namespace,a,[])!=='http://www.w3.org/1999/xhtml'||apply(indexOf,apply(localName,a,[]),['-'])!==-1||apply(shadow,a,[])||
                    apply(matches,a,[forbidden])||apply(attr,a,['hidden'])!==null||apply(attr,a,['inert'])!==null||apply(attr,a,['aria-disabled'])==='true')return out('UNSUPPORTED');
                  const style=apply(computed,w,[a]);if(!style)return out('UNSUPPORTED');
                  if(apply(styleValue,style,['display'])==='none'||apply(styleValue,style,['visibility'])!=='visible')return out('UNSUPPORTED');
                  const alpha=+apply(styleValue,style,['opacity']);if(alpha!==alpha||alpha===Infinity||alpha===-Infinity)return out('UNSUPPORTED');opacity*=alpha;
                  if(a===root)break;
                }
                if(opacity<=0)return out('UNSUPPORTED');
                const walker=apply(createWalker,d,[e,1,null]);if(!walker)return out('UNSUPPORTED');let count=0,child;
                while((child=apply(nextNode,walker,[]))!==null){if(++count>64)return out('UNSUPPORTED');
                  if(apply(namespace,child,[])!=='http://www.w3.org/1999/xhtml'||
                    apply(indexOf,apply(localName,child,[]),['-'])!==-1||apply(shadow,child,[])||apply(matches,child,[forbidden]))return out('UNSUPPORTED')}
                if(tag==='button'&&disabled&&apply(disabled,e,[]))return out('UNSUPPORTED');
                const stack=apply(elementsAt,d,[x,y]);if(!stack||stack.length===0||stack[0]!==e)return out('STALE_SCREENSHOT');
                const r=apply(rect,e,[]),style=apply(computed,w,[e]);if(!r||!style||r.width<=0||r.height<=0||!apply(connected,e,[])||
                  apply(styleValue,style,['display'])==='none'||apply(styleValue,style,['visibility'])!=='visible'||
                  +apply(styleValue,style,['opacity'])===0)return out('UNSUPPORTED');
                if(!apply(authorizeVisual,gate,[p.nonce]))return out('AUTHORIZATION_REVOKED');
                if(surface)return out('NATIVE_READY');
                dispatched=true;apply(nativeClick,e,[]);return out('OK');
              }catch(_){return out(dispatched?'UNKNOWN':'UNSUPPORTED')}};
              const api=freeze({read,click});try{Object.defineProperty(w,k,{value:api,writable:false,configurable:false,enumerable:false})}catch(_){}
            })();
        """.trimIndent()
        return runCatching {
            handlers[view] = WebViewCompat.addDocumentStartJavaScript(view, source, setOf("*"))
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun remove(view: WebView) { runCatching { handlers.remove(view)?.remove() } }

    fun probe(): String = """(()=>{try{const a=window[$keyLiteral];return a&&typeof a.read==='function'?a.read():null}catch(_){return null}})()"""

    fun click(
        point: BrowserWebViewPoint,
        identity: BrowserVisualIdentity,
        nonce: String,
        nativeSurface: Boolean = false,
    ): String = """(()=>{try{const a=window[$keyLiteral];if(!a||typeof a.click!=='function')return '{"code":"UNSUPPORTED"}';
      return a.click({x:${point.x},y:${point.y},documentToken:${JSONObject.quote(identity.documentToken)},
        mutationGeneration:${identity.mutationGeneration},visualViewportWidth:${identity.visualViewportWidth},
        visualViewportHeight:${identity.visualViewportHeight},visualViewportOffsetX:${identity.visualViewportOffsetX},
        visualViewportOffsetY:${identity.visualViewportOffsetY},nonce:${JSONObject.quote(nonce)},nativeSurface:$nativeSurface})}catch(_){return '{"code":"UNKNOWN"}'}})()"""

    private val keyLiteral get() = JSONObject.quote(key)
}
