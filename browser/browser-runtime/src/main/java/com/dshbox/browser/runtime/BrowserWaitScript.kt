package com.dshbox.browser.runtime

import org.json.JSONObject

/** Builds read-only, result-only page probes for bounded condition waits. */
internal object BrowserWaitScript {
    const val SELECTOR_SCAN_LIMIT = 5000

    fun condition(condition: String, args: JSONObject): String = when (condition) {
        "url" -> "(() => location.href === ${JSONObject.quote(args.getString("url"))})()"
        "text" -> text(args.getString("text"))
        "selector" -> selector(args.getString("selector"), args.optString("state", "visible"))
        else -> error("Unsupported DOM wait condition")
    }

    private fun text(expected: String): String = """
        (() => {
          const wanted = ${JSONObject.quote(expected)}.replace(/\s+/g, ' ').trim();
          const root = document.body;
          if (!root) return false;
          const visible = element => {
            for (let node = element; node && node.nodeType === 1; node = node.parentElement) {
              const style = window.getComputedStyle(node);
              if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || style.opacity === '0' || node.hasAttribute('hidden') || node.getAttribute('aria-hidden') === 'true') return false;
            }
            const rect = element.getBoundingClientRect();
            return element.getClientRects().length > 0 && rect.width > 0 && rect.height > 0;
          };
          const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
          let node, visited = 0, inspectedCharacters = 0, tail = '', previousBlock = null;
          const blockOf = element => {
            for (let current = element; current; current = current.parentElement) {
              const display = window.getComputedStyle(current).display;
              if (!['inline', 'contents', 'inline-block'].includes(display)) return current;
            }
            return root;
          };
          while ((node = walker.nextNode())) {
            if (++visited > 5000) return 'WAIT_PROBE_LIMIT';
            const parent = node.parentElement;
            if (!parent || parent.closest('script,style,noscript,template,input,textarea,select,[type=password],[contenteditable],[role="textbox"],[role="searchbox"],[role="combobox"],[role="spinbutton"],[role="slider"]') || !visible(parent)) {
              tail = ''; previousBlock = null; continue;
            }
            const block = blockOf(parent);
            if (previousBlock && previousBlock !== block) tail += ' ';
            previousBlock = block;
            const value = node.nodeValue || '';
            for (let offset = 0; offset < value.length; offset += 2048) {
              const part = value.slice(offset, offset + 2048);
              inspectedCharacters += part.length;
              if (inspectedCharacters > 200000) return 'WAIT_PROBE_LIMIT';
              tail = (tail + part).replace(/\s+/g, ' ');
              if (tail.includes(wanted)) return true;
              tail = tail.slice(-Math.max(1, wanted.length));
            }
          }
          return false;
        })()
    """.trimIndent()

    private fun selector(selector: String, state: String): String = """
        (() => {
          const selectorText = ${JSONObject.quote(selector)};
          const consumeEscape = (text, slash) => {
            let index = slash + 1;
            if (index >= text.length) return ['', index];
            if (/[0-9a-fA-F]/.test(text[index])) {
              const start = index;
              while (index < text.length && index - start < 6 && /[0-9a-fA-F]/.test(text[index])) index++;
              const codePoint = parseInt(text.slice(start, index), 16);
              if (index < text.length && /\s/.test(text[index])) {
                const whitespace = text[index++];
                if (whitespace === '\r' && text[index] === '\n') index++;
              }
              return [String.fromCodePoint(codePoint === 0 || codePoint > 0x10ffff ? 0xfffd : codePoint), index];
            }
            if (text[index] === '\r') return ['', text[index + 1] === '\n' ? index + 2 : index + 1];
            if (text[index] === '\n' || text[index] === '\f') return ['', index + 1];
            return [text[index], index + 1];
          };
          const isNameCharacter = character => /[a-zA-Z0-9_-]/.test(character) || character.charCodeAt(0) >= 0x80;
          const readIdentifier = (text, start) => {
            let index = start, value = '';
            while (index < text.length) {
              if (text[index] === '\\') {
                const [decoded, next] = consumeEscape(text, index);
                value += decoded;
                index = next;
              } else if (isNameCharacter(text[index])) {
                value += text[index++];
              } else break;
            }
            return [value, index];
          };
          const hasScopePseudo = text => {
            let quote = '', brackets = 0;
            for (let index = 0; index < text.length;) {
              const character = text[index];
              if (quote) {
                if (character === '\\') { index = consumeEscape(text, index)[1]; continue; }
                if (character === quote) quote = '';
                index++;
                continue;
              }
              if (character === '/' && text[index + 1] === '*') {
                const end = text.indexOf('*/', index + 2);
                if (end < 0) return false;
                index = end + 2;
                continue;
              }
              if (character === '\\') { index = consumeEscape(text, index)[1]; continue; }
              if (character === '"' || character === "'") { quote = character; index++; continue; }
              if (character === '[') { brackets++; index++; continue; }
              if (character === ']' && brackets > 0) { brackets--; index++; continue; }
              if (brackets > 0) { index++; continue; }
              if (character === ':' && text[index + 1] !== ':') {
                const [name, next] = readIdentifier(text, index + 1);
                if (name.toLowerCase() === 'scope') return true;
                index = next;
                continue;
              }
              index++;
            }
            return false;
          };
          if (hasScopePseudo(selectorText)) return 'INVALID_SELECTOR';
          try { document.documentElement.matches(selectorText); }
          catch (_) { return 'INVALID_SELECTOR'; }
          const state = ${JSONObject.quote(state)};
          const visible = element => {
            const rect = element.getBoundingClientRect();
            if (!element.getClientRects().length || rect.width <= 0 || rect.height <= 0) return false;
            for (let node = element; node && node.nodeType === 1; node = node.parentElement) {
              const style = window.getComputedStyle(node);
              if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || style.opacity === '0' || node.hasAttribute('hidden') || node.getAttribute('aria-hidden') === 'true') return false;
            }
            return true;
          };
          const walker = document.createTreeWalker(document, NodeFilter.SHOW_ELEMENT);
          let element, visited = 0;
          while ((element = walker.nextNode())) {
            if (++visited > __SELECTOR_LIMIT__) return 'WAIT_PROBE_LIMIT';
            let matches;
            try { matches = element.matches(selectorText); }
            catch (_) { return 'INVALID_SELECTOR'; }
            if (!matches) continue;
            if (state === 'attached') return true;
            const isVisible = visible(element);
            if (state === 'visible' && isVisible) return true;
            if (state === 'hidden' && isVisible) return false;
          }
          return state === 'visible' ? false : state === 'hidden';
        })()
    """.trimIndent().replace("__SELECTOR_LIMIT__", SELECTOR_SCAN_LIMIT.toString())
}
