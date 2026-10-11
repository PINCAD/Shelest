import { sharedCore } from "./sharedCore";

export function normalizeAnchor(text: string): string { return sharedCore.normalizeAnchor(text); }

/** Whitespace-independent text identity shared with Android; reject ambiguous matches. */
export function anchorOffset(text: string, anchor: string): number | null {
  const offset = sharedCore.anchorOffset(text, anchor);
  return offset < 0 ? null : offset;
}

export function rawOffsetForNormalized(text: string, target: number): number {
  return sharedCore.rawAnchorOffset(text, target);
}

export function caretAtPoint(x: number, y: number): Range | null {
  const doc = document as any;
  if (doc.caretRangeFromPoint) return doc.caretRangeFromPoint(x, y);
  const point = doc.caretPositionFromPoint?.(x, y);
  if (!point) return null;
  const range = document.createRange(); range.setStart(point.offsetNode, point.offset); range.collapse(true); return range;
}

export function textRange(root: Node, offset: number): Range | null {
  const doc = root.ownerDocument!;
  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: node => node.parentElement?.closest("script,style") ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
  });
  let node: Node | null;
  while ((node = walker.nextNode())) {
    const length = node.textContent?.length ?? 0;
    if (offset < length) {
      const range = doc.createRange(); range.setStart(node, offset); range.setEnd(node, Math.min(offset + 1, length)); return range;
    }
    offset -= length;
  }
  return null;
}

export function quoteAtRange(root: Node, point: Range): string {
  const range = point.cloneRange(); range.setEnd(root, root.childNodes.length);
  return normalizeAnchor(range.toString().slice(0, 500)).slice(0, 160);
}
