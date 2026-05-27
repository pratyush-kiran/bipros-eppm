import { useCallback, useRef, useState } from "react";

export function useStickyMeasure<T extends HTMLElement>() {
  const [height, setHeight] = useState(0);
  const elRef = useRef<T | null>(null);
  const roRef = useRef<ResizeObserver | null>(null);

  const ref = useCallback((el: T | null) => {
    if (roRef.current) {
      roRef.current.disconnect();
      roRef.current = null;
    }
    elRef.current = el;
    if (!el) {
      setHeight(0);
      return;
    }
    setHeight(el.offsetHeight);
    const ro = new ResizeObserver(() => {
      // Must be the border-box height (offsetHeight) so a sibling sticky element can park
      // flush beneath this one. ResizeObserver's `contentRect` is the *content* box — it drops
      // padding + border, which would make the next sticky header park that many pixels too
      // high and tuck behind this one (z-stacked). offsetHeight matches the initial read above.
      setHeight(el.offsetHeight);
    });
    ro.observe(el);
    roRef.current = ro;
  }, []);

  return { ref, height };
}
