package com.dshbox.browser.runtime

/**
 * Builds a read-only, bounded JavaScript observer for top-document overlays.
 * Existing observation indexes are supplied by the caller and are never created here.
 */
internal object BrowserOverlayObservation {
    const val CANDIDATE_SCAN_LIMIT = 256
    const val VIEWPORT_SAMPLE_LIMIT = 25
    const val OVERLAY_OUTPUT_LIMIT = 8

    fun script(): String = """
      (input) => {
        "use strict";
        const limits = {
          candidateScan: 256,
          viewportSamples: 25,
          overlays: 8,
          registeredActionable: 80,
          dismissCandidates: 16,
          dismissPerOverlay: 4,
          ancestorScan: 32,
          nameTextNodes: 64,
          totalTextNodes: 512,
          textChars: 8192,
          errors: 4
        };
        const state = {
          candidateScan: 0,
          viewportSamples: 0,
          textNodes: 0,
          textChars: 0,
          textLimitReached: false,
          candidateLimitReached: false,
          partial: false,
          errors: [],
          overlays: []
        };
        const clean = (value, limit) => {
          if (state.textChars >= limits.textChars) {
            state.textLimitReached = true;
            return "";
          }
          const raw = String(value == null ? "" : value).slice(0, limit * 4 + 32);
          const result = raw.replace(/\s+/g, " ").trim().slice(0, Math.min(limit, limits.textChars - state.textChars));
          state.textChars = Math.min(limits.textChars, state.textChars + result.length);
          if (result.length < raw.replace(/\s+/g, " ").trim().length) state.textLimitReached = true;
          return result;
        };
        const failSoft = () => {
          state.partial = true;
          if (state.errors.length < limits.errors) {
            const reason = "bounded_observation_error";
            if (!state.errors.includes(reason)) state.errors.push(reason);
          }
        };
        const finite = value => Number.isFinite(Number(value));
        const viewport = {
          width: Math.max(0, Number(window.innerWidth || document.documentElement.clientWidth || 0)),
          height: Math.max(0, Number(window.innerHeight || document.documentElement.clientHeight || 0))
        };
        const viewportArea = viewport.width * viewport.height;
        const rectOf = element => {
          const rect = element.getBoundingClientRect();
          return {
            left: Number(rect.left),
            top: Number(rect.top),
            right: Number(rect.right),
            bottom: Number(rect.bottom),
            width: Number(rect.width),
            height: Number(rect.height)
          };
        };
        const finiteRect = rect => rect && [rect.left, rect.top, rect.right, rect.bottom, rect.width, rect.height]
          .every(finite);
        const intersection = rect => {
          const left = Math.max(0, Math.min(viewport.width, rect.left));
          const top = Math.max(0, Math.min(viewport.height, rect.top));
          const right = Math.max(0, Math.min(viewport.width, rect.right));
          const bottom = Math.max(0, Math.min(viewport.height, rect.bottom));
          return {left, top, right, bottom, width: Math.max(0, right - left), height: Math.max(0, bottom - top)};
        };
        const visible = element => {
          if (!element || element.nodeType !== 1 || !element.isConnected || element.hidden ||
              element.getAttribute("aria-hidden") === "true") return false;
          let current = element;
          let ancestorSteps = 0;
          while (current && ancestorSteps < limits.ancestorScan) {
            const style = window.getComputedStyle(current);
            if (style.display === "none" || style.visibility === "hidden" || style.visibility === "collapse" ||
                style.contentVisibility === "hidden" || Number(style.opacity) === 0) return false;
            if (current.parentElement) current = current.parentElement;
            else {
              const root = current.getRootNode();
              current = root && root.host ? root.host : null;
            }
            ancestorSteps++;
          }
          if (current) {
            failSoft();
            return false;
          }
          const rect = rectOf(element);
          return finiteRect(rect) && rect.width > 0 && rect.height > 0 &&
            element.getClientRects().length > 0;
        };
        const boundedDirectText = element => {
          const parts = [];
          let childNodesVisited = 0;
          const children = element.childNodes || [];
          for (let index = 0;
               index < children.length && childNodesVisited < limits.nameTextNodes;
               index++) {
            const child = children[index];
            childNodesVisited++;
            if (!child || child.nodeType !== 3) continue;
            if (state.textNodes >= limits.totalTextNodes) {
              state.textLimitReached = true;
              break;
            }
            state.textNodes++;
            parts.push(String(child.nodeValue || "").slice(0, 512));
          }
          return clean(parts.join(" "), 160);
        };
        const accessibleName = element => {
          try {
            const labelledBy = String(element.getAttribute("aria-labelledby") || "").slice(0, 2048).trim()
              .split(/\s+/, 8).filter(Boolean);
            if (labelledBy.length) {
              const parts = [];
              for (let index = 0; index < labelledBy.length && index < 8; index++) {
                const target = document.getElementById(labelledBy[index]);
                if (target) parts.push(boundedDirectText(target));
              }
              const joined = clean(parts.join(" "), 160);
              if (joined) return joined;
            }
            const ariaLabel = clean(element.getAttribute("aria-label"), 160);
            if (ariaLabel) return ariaLabel;
            if (element.labels && element.labels.length) {
              const parts = [];
              const labelCount = Math.min(element.labels.length, 8);
              for (let index = 0; index < labelCount; index++) parts.push(boundedDirectText(element.labels[index]));
              const joined = clean(parts.join(" "), 160);
              if (joined) return joined;
            }
            const nestedLabel = element.closest("label");
            if (nestedLabel) {
              const nestedName = boundedDirectText(nestedLabel);
              if (nestedName) return nestedName;
            }
            const tag = element.tagName.toLowerCase();
            if (tag === "img") return clean(element.getAttribute("alt"), 160);
            const title = clean(element.getAttribute("title"), 160);
            if (title) return title;
            if (tag === "input") return clean(element.getAttribute("placeholder"), 160);
            return boundedDirectText(element);
          } catch (_) {
            failSoft();
            return "";
          }
        };
        const implicitRole = element => {
          const tag = element.tagName.toLowerCase();
          const type = String(element.getAttribute("type") || "").toLowerCase();
          if (tag === "button" || tag === "summary") return "button";
          if (tag === "a" && element.hasAttribute("href")) return "link";
          if (tag === "dialog") return "dialog";
          if (tag === "textarea") return "textbox";
          if (tag === "select") return element.multiple || Number(element.size) > 1 ? "listbox" : "combobox";
          if (tag === "img") return element.getAttribute("alt") === "" ? "presentation" : "img";
          if (tag === "input") {
            if (type === "checkbox") return "checkbox";
            if (type === "radio") return "radio";
            if (type === "submit" || type === "button" || type === "reset" || type === "image") return "button";
            if (type === "search") return "searchbox";
            if (type === "range") return "slider";
            if (type === "number") return "spinbutton";
            return "textbox";
          }
          return "";
        };
        const roleOf = element => clean(
          String(element.getAttribute("role") || "").trim().split(/\s+/)[0] || implicitRole(element), 48
        );
        const registeredSource = Array.isArray(input && input.registeredActionable)
          ? input.registeredActionable.slice(0, limits.registeredActionable)
          : [];
        const referenceMap = input && Array.isArray(input.referenceMap)
          ? new Map(input.referenceMap.slice(0, limits.registeredActionable))
          : input && input.referenceMap && typeof input.referenceMap.get === "function" &&
            typeof input.referenceMap.has === "function"
            ? input.referenceMap
            : new Map();
        const seen = new Set();
        const candidates = [];
        const evidenceFor = (element, style, rect) => {
          const tag = element.tagName.toLowerCase();
          const role = String(element.getAttribute("role") || "").trim().toLowerCase();
          const semantic = clean(
            String(element.id || "") + " " + String(element.getAttribute("class") || ""), 160
          );
          const semanticMatch = /\b(dialog|modal|overlay|popup|backdrop|mask|shade)\b/i.test(semantic);
          const fixed = style.position === "fixed";
          const sticky = style.position === "sticky";
          const dialog = tag === "dialog" && (element.open === true || element.hasAttribute("open"));
          const modal = role === "dialog" || role === "alertdialog" || element.getAttribute("aria-modal") === "true";
          const zIndex = finite(style.zIndex) ? Number(style.zIndex) : null;
          const clipped = intersection(rect);
          const viewportCoverage = viewportArea > 0 ? clipped.width * clipped.height / viewportArea : 0;
          const qualifies = dialog || modal ||
            ((fixed || sticky) && viewportCoverage >= 0.18) ||
            (semanticMatch && viewportCoverage >= 0.12 && zIndex !== null && zIndex > 0);
          return {fixed, sticky, dialog, modal, zIndex, semantic, semanticMatch, viewportCoverage, qualifies};
        };
        const consider = element => {
          if (state.candidateScan >= limits.candidateScan) {
            state.candidateLimitReached = true;
            return false;
          }
          if (!element || element.nodeType !== 1 || seen.has(element)) return state.candidateScan < limits.candidateScan;
          seen.add(element);
          state.candidateScan++;
          if (state.candidateScan >= limits.candidateScan) state.candidateLimitReached = true;
          try {
            if (!visible(element)) return state.candidateScan < limits.candidateScan;
            const rect = rectOf(element);
            if (!finiteRect(rect)) return state.candidateScan < limits.candidateScan;
            const clipped = intersection(rect);
            if (clipped.width <= 0 || clipped.height <= 0) return state.candidateScan < limits.candidateScan;
            const evidence = evidenceFor(element, window.getComputedStyle(element), rect);
            if (evidence.qualifies) {
              candidates.push({element, rect, evidence});
            }
          } catch (_) {
            failSoft();
          }
          return state.candidateScan < limits.candidateScan;
        };
        try {
          for (let index = 0; index < registeredSource.length; index++) {
            const element = registeredSource[index];
            if (!element || element.nodeType !== 1) continue;
            let current = element;
            for (let depth = 0; depth < 8 && current; depth++) {
              if (!consider(current)) break;
              current = current.parentElement;
            }
            if (state.candidateScan >= limits.candidateScan) break;
          }
          if (state.candidateScan < limits.candidateScan) {
            const walker = document.createTreeWalker(document, NodeFilter.SHOW_ELEMENT);
            let element;
            while ((element = walker.nextNode())) {
              if (!consider(element)) break;
            }
          }
        } catch (error) {
          failSoft(error);
        }
        candidates.sort((left, right) => {
          const leftScore = (left.evidence.dialog ? 8 : 0) + (left.evidence.modal ? 4 : 0) +
            left.evidence.viewportCoverage * 2 + (left.evidence.zIndex == null ? 0 : Math.min(1, left.evidence.zIndex / 100));
          const rightScore = (right.evidence.dialog ? 8 : 0) + (right.evidence.modal ? 4 : 0) +
            right.evidence.viewportCoverage * 2 + (right.evidence.zIndex == null ? 0 : Math.min(1, right.evidence.zIndex / 100));
          return rightScore - leftScore;
        });
        const selected = candidates.slice(0, limits.overlays);
        const outputTruncated = candidates.length > selected.length;
        const pseudoContent = element => {
          const result = [];
          for (const source of ["before", "after"]) {
            try {
              const style = window.getComputedStyle(element, "::" + source);
              const content = String(style.content || "").slice(0, 640);
              if (!content || content === "none" || content === "normal") continue;
              result.push({source, boundedContent: clean(content, 160)});
            } catch (_) {
              failSoft();
            }
          }
          return result.slice(0, 2);
        };
        const closeLike = (element, name) => {
          const identity = clean(
            String(element.id || "") + " " + String(element.getAttribute("class") || "") + " " +
            String(element.getAttribute("aria-label") || "") + " " + String(element.getAttribute("title") || "") +
            " " + name, 512
          );
          return /\b(close|dismiss|cancel|skip|exit)\b|关闭|关闭|取消|跳过|退出/i.test(identity);
        };
        const enabled = element => !element.disabled && element.getAttribute("aria-disabled") !== "true";
        const referenceIndex = element => {
          if (!referenceMap.has(element)) return null;
          const value = referenceMap.get(element);
          return Number.isInteger(value) && value >= 0 && value < limits.registeredActionable ? value : null;
        };
        const topHitAt = (x, y) => {
          if (typeof document.elementsFromPoint === "function") {
            const stack = document.elementsFromPoint(x, y);
            return stack && stack.length > 0 ? stack[0] : null;
          }
          return document.elementFromPoint(x, y);
        };
        const associatedLabelHit = (control, hit) => {
          if (!hit || hit.nodeType !== 1 || hit.tagName !== "LABEL") return false;
          if (hit.control === control) return true;
          if (control.id && hit.getAttribute("for") === control.id) return true;
          if (control.labels && control.labels.length) {
            const labelCount = Math.min(control.labels.length, 8);
            for (let index = 0; index < labelCount; index++) {
              if (control.labels[index] === hit) return true;
            }
          }
          return false;
        };
        const dismissPlans = selected.map(() => []);
        let plannedDismissCount = 0;
        for (let overlayIndex = 0; overlayIndex < selected.length; overlayIndex++) {
          const overlayElement = selected[overlayIndex].element;
          for (let index = 0;
               index < registeredSource.length && dismissPlans[overlayIndex].length < limits.dismissPerOverlay;
               index++) {
            const control = registeredSource[index];
            const refIndex = referenceIndex(control);
            if (refIndex == null || !overlayElement.contains(control) || !visible(control) || !enabled(control)) continue;
            const name = accessibleName(control);
            if (!closeLike(control, name)) continue;
            dismissPlans[overlayIndex].push({control, refIndex, name});
            plannedDismissCount++;
          }
        }
        const dismissSampleBudget = Math.min(plannedDismissCount, 7);
        let remainingOcclusionSamples = limits.viewportSamples - dismissSampleBudget;
        let remainingDismissSamples = dismissSampleBudget;
        let remainingViewportSamples = limits.viewportSamples;
        let remainingOverlays = selected.length;
        const takeViewportSample = () => {
          if (remainingViewportSamples <= 0) return false;
          remainingViewportSamples--;
          state.viewportSamples++;
          return true;
        };
        for (let overlayIndex = 0; overlayIndex < selected.length; overlayIndex++) {
          const candidate = selected[overlayIndex];
          const element = candidate.element;
          const rect = candidate.rect;
          const clipped = intersection(rect);
          const kind = candidate.evidence.dialog ? "dialog" :
            candidate.evidence.modal ? "modal" :
            candidate.evidence.fixed ? "fixed" :
            candidate.evidence.sticky ? "sticky" : "layer";
          const sampleLimit = Math.max(1, Math.min(
            remainingOcclusionSamples,
            Math.ceil(remainingOcclusionSamples / Math.max(1, remainingOverlays))
          ));
          let sampled = 0;
          let covered = 0;
          for (let sampleIndex = 0;
               sampleIndex < sampleLimit && remainingOcclusionSamples > 0;
               sampleIndex++) {
            if (!takeViewportSample()) break;
            remainingOcclusionSamples--;
            sampled++;
            try {
              const columns = Math.min(5, sampleLimit);
              const rows = Math.ceil(sampleLimit / columns);
              const column = sampleIndex % columns;
              const row = Math.floor(sampleIndex / columns);
              const xRatio = (column + 0.5) / columns;
              const yRatio = (row + 0.5) / rows;
              const x = clipped.left + clipped.width * xRatio;
              const y = clipped.top + clipped.height * yRatio;
              const hit = topHitAt(x, y);
              if (hit === element || element.contains(hit)) covered++;
            } catch (_) {
              failSoft();
            }
          }
          const dismissCandidates = [];
          for (const planned of dismissPlans[overlayIndex]) {
            if (dismissCandidates.length >= limits.dismissPerOverlay || remainingDismissSamples <= 0) break;
            const control = planned.control;
            const controlRect = rectOf(control);
            if (!finiteRect(controlRect)) continue;
            const controlViewport = intersection(controlRect);
            if (controlViewport.width <= 0 || controlViewport.height <= 0) continue;
            if (!takeViewportSample()) break;
            remainingDismissSamples--;
            try {
              const hit = topHitAt(
                controlViewport.left + controlViewport.width / 2,
                controlViewport.top + controlViewport.height / 2
              );
              const hitIsControl = hit === control || control.contains(hit) ||
                associatedLabelHit(control, hit);
              if (!hitIsControl) continue;
              dismissCandidates.push({
                refIndex: planned.refIndex,
                tag: control.tagName.toLowerCase(),
                role: roleOf(control),
                name: planned.name,
                box: {
                  x: controlRect.left,
                  y: controlRect.top,
                  width: controlRect.width,
                  height: controlRect.height
                }
              });
            } catch (_) {
              failSoft();
            }
          }
          const pseudo = pseudoContent(element);
          const overlay = {
            kind,
            tag: element.tagName.toLowerCase(),
            role: roleOf(element),
            name: accessibleName(element),
            box: {x: rect.left, y: rect.top, width: rect.width, height: rect.height},
            viewportBox: {x: clipped.left, y: clipped.top, width: clipped.width, height: clipped.height},
            viewportCoverageRatio: Number(candidate.evidence.viewportCoverage.toFixed(4)),
            evidence: {
              fixed: candidate.evidence.fixed,
              sticky: candidate.evidence.sticky,
              dialog: candidate.evidence.dialog,
              modal: candidate.evidence.modal,
              zIndex: candidate.evidence.zIndex,
              semanticHint: candidate.evidence.semantic
            },
            occlusion: {
              sampled,
              covered,
              ratio: sampled > 0 ? Number((covered / sampled).toFixed(4)) : null
            },
            pseudo,
            dismiss: dismissCandidates.length > 0
              ? {status: "available", candidates: dismissCandidates}
              : {status: "none", candidates: []}
          };
          state.overlays.push(overlay);
          remainingOverlays--;
        }
        return {
          overlays: state.overlays.slice(0, limits.overlays),
          scope: {
            topFrameOnly: true,
            includesCrossOriginFrames: false,
            frameCoverage: "not_inspected",
            complete: false
          },
          coverage: {
            partial: state.partial,
            candidateScan: state.candidateScan,
            candidateScanLimit: limits.candidateScan,
            candidateLimitReached: state.candidateLimitReached,
            viewportSampleCount: state.viewportSamples,
            viewportSampleLimit: limits.viewportSamples,
            overlayOutputCount: Math.min(state.overlays.length, limits.overlays),
            overlayOutputLimit: limits.overlays,
            outputTruncated,
            textNodesVisited: state.textNodes,
            textChars: state.textChars,
            textLimitReached: state.textLimitReached,
            errors: state.errors.slice(0, limits.errors)
          },
          action: {
            performed: false,
            automaticDismiss: false,
            captchaSolved: false
          }
        };
      }
    """.trimIndent()
}
