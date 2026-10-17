package com.dshbox.browser.runtime

/** Page-wide layout zoom; it does not change WebSettings.textZoom or the browser chrome. */
internal fun pageZoomExpression(percent: Int): String {
    require(percent in 70..150)
    return """(() => {
      try {
        const root=document.documentElement;
        if(!root||!CSS.supports('zoom','1'))return JSON.stringify({ok:false});
        const key='__dshbox_page_zoom_v1';
        let state=root[key];
        if(!state){
          const computed=parseFloat(getComputedStyle(root).zoom);
          state={base:Number.isFinite(computed)&&computed>0?computed:1,
            value:root.style.getPropertyValue('zoom'),priority:root.style.getPropertyPriority('zoom')};
          Object.defineProperty(root,key,{value:state,configurable:true});
        }
        if($percent===100){
          if(state.value)root.style.setProperty('zoom',state.value,state.priority);
          else root.style.removeProperty('zoom');
        }else root.style.setProperty('zoom',String(state.base*$percent/100),'important');
        const actual=parseFloat(getComputedStyle(root).zoom);
        if(!Number.isFinite(actual)||actual<=0)return JSON.stringify({ok:false});
        const measured=Math.round(actual/state.base*100);
        return JSON.stringify({ok:Math.abs(measured-$percent)<=1,percent:measured});
      }catch(_){return JSON.stringify({ok:false});}
    })()"""
}
